from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field

APP_VERSION = "0.1.0"
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
DATA_DIR = Path(os.getenv("UMNIK_DATA_DIR", "/var/lib/umnik-server"))
DB_PATH = DATA_DIR / "jobs.sqlite3"
OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "").strip()
SERVER_TOKEN = os.getenv("UMNIK_SERVER_TOKEN", "").strip()

app = FastAPI(title="Umnik Personal Server", version=APP_VERSION)


class ChatJobRequest(BaseModel):
    client_request_id: str = Field(min_length=8, max_length=128)
    payload: dict[str, Any]


class ChatJobView(BaseModel):
    id: str
    client_request_id: str
    status: str
    response: dict[str, Any] | None = None
    error: str | None = None
    created_at: int
    updated_at: int


@contextmanager
def db():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                client_request_id TEXT NOT NULL UNIQUE,
                payload_sha256 TEXT NOT NULL,
                status TEXT NOT NULL,
                response_json TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """
        )
        conn.execute(
            "UPDATE jobs SET status='failed', error='Server restarted before the request completed', updated_at=? "
            "WHERE status IN ('queued','running')",
            (int(time.time()),),
        )


def require_config() -> None:
    if not OPENROUTER_API_KEY:
        raise RuntimeError("OPENROUTER_API_KEY is not configured")
    if not SERVER_TOKEN:
        raise RuntimeError("UMNIK_SERVER_TOKEN is not configured")


def auth(authorization: str | None = Header(default=None)) -> None:
    expected = f"Bearer {SERVER_TOKEN}"
    if not SERVER_TOKEN or not authorization or not hmac.compare_digest(authorization, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Unauthorized")


def payload_hash(payload: dict[str, Any]) -> str:
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def get_job_by_client_request_id(client_request_id: str):
    with db() as conn:
        return conn.execute(
            "SELECT * FROM jobs WHERE client_request_id=?", (client_request_id,)
        ).fetchone()


def get_job(job_id: str):
    with db() as conn:
        return conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()


def row_to_view(row: sqlite3.Row) -> ChatJobView:
    response = json.loads(row["response_json"]) if row["response_json"] else None
    return ChatJobView(
        id=row["id"],
        client_request_id=row["client_request_id"],
        status=row["status"],
        response=response,
        error=row["error"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )


def update_job(job_id: str, *, state: str, response: dict[str, Any] | None = None, error: str | None = None) -> None:
    with db() as conn:
        conn.execute(
            "UPDATE jobs SET status=?, response_json=?, error=?, updated_at=? WHERE id=?",
            (
                state,
                json.dumps(response, ensure_ascii=False) if response is not None else None,
                error,
                int(time.time()),
                job_id,
            ),
        )


async def run_job(job_id: str, payload: dict[str, Any]) -> None:
    update_job(job_id, state="running")
    forwarded = dict(payload)
    forwarded["stream"] = False
    headers = {
        "Authorization": f"Bearer {OPENROUTER_API_KEY}",
        "Content-Type": "application/json",
        "X-Title": "Umnik Personal Server",
    }
    try:
        timeout = httpx.Timeout(connect=30.0, read=600.0, write=120.0, pool=30.0)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
            response = await client.post(OPENROUTER_URL, headers=headers, json=forwarded)
        body_text = response.text
        if response.status_code < 200 or response.status_code >= 300:
            message = body_text[:4000] or f"OpenRouter HTTP {response.status_code}"
            update_job(job_id, state="failed", error=message)
            return
        try:
            parsed = response.json()
        except ValueError:
            update_job(job_id, state="failed", error="OpenRouter returned invalid JSON")
            return
        update_job(job_id, state="completed", response=parsed)
    except Exception as exc:
        # Deliberately no automatic retry here. A retry could create a second paid
        # generation if the upstream accepted the first request before the failure.
        update_job(job_id, state="failed", error=f"{type(exc).__name__}: {exc}")


@app.on_event("startup")
def startup() -> None:
    require_config()
    init_db()


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True, "service": "umnik-personal-server", "version": APP_VERSION}


@app.get("/v1/capabilities", dependencies=[Depends(auth)])
def capabilities() -> dict[str, Any]:
    return {
        "protocol": 1,
        "durable_chat_jobs": True,
        "idempotent_client_request_id": True,
        "streaming": False,
    }


@app.post("/v1/chat/jobs", response_model=ChatJobView, status_code=status.HTTP_202_ACCEPTED, dependencies=[Depends(auth)])
async def create_chat_job(request: ChatJobRequest) -> ChatJobView:
    if "model" not in request.payload or "messages" not in request.payload:
        raise HTTPException(status_code=400, detail="payload must contain model and messages")
    digest = payload_hash(request.payload)
    existing = get_job_by_client_request_id(request.client_request_id)
    if existing is not None:
        if existing["payload_sha256"] != digest:
            raise HTTPException(status_code=409, detail="client_request_id is already used for a different payload")
        return row_to_view(existing)

    now = int(time.time())
    job_id = str(uuid.uuid4())
    with db() as conn:
        try:
            conn.execute(
                "INSERT INTO jobs(id, client_request_id, payload_sha256, status, created_at, updated_at) VALUES(?,?,?,?,?,?)",
                (job_id, request.client_request_id, digest, "queued", now, now),
            )
        except sqlite3.IntegrityError:
            existing = conn.execute(
                "SELECT * FROM jobs WHERE client_request_id=?", (request.client_request_id,)
            ).fetchone()
            if existing is None:
                raise
            if existing["payload_sha256"] != digest:
                raise HTTPException(status_code=409, detail="client_request_id is already used for a different payload")
            return row_to_view(existing)

    asyncio.create_task(run_job(job_id, request.payload))
    created = get_job(job_id)
    assert created is not None
    return row_to_view(created)


@app.get("/v1/chat/jobs/{job_id}", response_model=ChatJobView, dependencies=[Depends(auth)])
def read_chat_job(job_id: str) -> ChatJobView:
    row = get_job(job_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Job not found")
    return row_to_view(row)
