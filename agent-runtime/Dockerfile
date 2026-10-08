FROM python:3.12-slim
WORKDIR /app
COPY py/aegislib /opt/aegislib
RUN pip install --no-cache-dir /opt/aegislib "fastapi>=0.115" "uvicorn>=0.32" "httpx>=0.27" "psycopg[binary]>=3.2" "opentelemetry-api>=1.27" "opentelemetry-sdk>=1.27" "opentelemetry-exporter-otlp-proto-http>=1.27"
COPY agent-runtime/app.py /app/app.py
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin aegis
USER aegis
EXPOSE 8090
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
