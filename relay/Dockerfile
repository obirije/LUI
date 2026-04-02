FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir websockets

COPY relay_server.py .

EXPOSE 9000

CMD ["python", "relay_server.py"]
