FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UMNIK_DATA_DIR=/var/lib/umnik-server

WORKDIR /opt/umnik-server

COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

RUN mkdir -p /var/lib/umnik-server && useradd --system --uid 10001 --create-home umnik && \
    chown -R umnik:umnik /var/lib/umnik-server /opt/umnik-server

USER umnik
EXPOSE 8787

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8787", "--proxy-headers"]
