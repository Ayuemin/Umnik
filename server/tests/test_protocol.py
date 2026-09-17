import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import HTTPException

from server.app import main


class ServerProtocolTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        main.DATA_DIR = Path(self.temp_dir.name)
        main.DB_PATH = main.DATA_DIR / "jobs.sqlite3"
        main.init_db()

    def tearDown(self):
        self.temp_dir.cleanup()

    @staticmethod
    def _discard_task(coroutine):
        coroutine.close()
        return None

    async def test_same_client_request_id_and_payload_reuses_existing_job(self):
        payload = {
            "model": "test/model",
            "messages": [{"role": "user", "content": "hello"}],
        }
        request = main.ChatJobRequest(client_request_id="request-12345678", payload=payload)
        with patch.object(main.asyncio, "create_task", side_effect=self._discard_task):
            first = await main.create_chat_job(request)
            second = await main.create_chat_job(request)

        self.assertEqual(first.id, second.id)
        self.assertEqual(first.client_request_id, second.client_request_id)

    async def test_same_client_request_id_with_different_payload_is_rejected(self):
        first = main.ChatJobRequest(
            client_request_id="request-12345678",
            payload={"model": "test/model", "messages": [{"role": "user", "content": "one"}]},
        )
        second = main.ChatJobRequest(
            client_request_id="request-12345678",
            payload={"model": "test/model", "messages": [{"role": "user", "content": "two"}]},
        )
        with patch.object(main.asyncio, "create_task", side_effect=self._discard_task):
            await main.create_chat_job(first)
            with self.assertRaises(HTTPException) as raised:
                await main.create_chat_job(second)

        self.assertEqual(409, raised.exception.status_code)

    async def test_restart_marks_unfinished_job_failed_instead_of_replaying_it(self):
        request = main.ChatJobRequest(
            client_request_id="request-87654321",
            payload={"model": "test/model", "messages": [{"role": "user", "content": "hello"}]},
        )
        with patch.object(main.asyncio, "create_task", side_effect=self._discard_task):
            created = await main.create_chat_job(request)
        main.update_job(created.id, state="running")

        main.init_db()
        restarted = main.get_job(created.id)

        self.assertIsNotNone(restarted)
        self.assertEqual("failed", restarted["status"])
        self.assertIn("restarted", restarted["error"].lower())


if __name__ == "__main__":
    unittest.main()
