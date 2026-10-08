FROM python:3.12-slim
WORKDIR /app
COPY py/aegislib /opt/aegislib
RUN pip install --no-cache-dir /opt/aegislib "httpx>=0.27" "psycopg[binary]>=3.2" "pypdf>=5.0" "boto3>=1.35" "opentelemetry-api>=1.27" "opentelemetry-sdk>=1.27" "opentelemetry-exporter-otlp-proto-http>=1.27"
COPY worker/worker.py /app/worker.py
COPY knowledge/seed /seed
ENV SEED_DIR=/seed
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin aegis
USER aegis
CMD ["python", "-u", "worker.py"]
