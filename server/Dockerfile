FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    ANIFORGE_RENDER=stub

RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY server/requirements.txt /srv/requirements.txt
RUN pip install --no-cache-dir -r /srv/requirements.txt

COPY server /srv/aniforge_server
RUN mv /srv/aniforge_server /app 2>/dev/null || true
ENV PYTHONPATH=/app/..
WORKDIR /app/..
CMD uvicorn aniforge_server.main:app --host 0.0.0.0 --port 8787
