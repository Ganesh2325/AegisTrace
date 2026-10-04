FROM python:3.12-slim
WORKDIR /app
COPY py/aegislib /opt/aegislib
RUN pip install --no-cache-dir /opt/aegislib "httpx>=0.27" "psycopg[binary]>=3.2" "pypdf>=5.0" "boto3>=1.35"
COPY worker/worker.py /app/worker.py
COPY knowledge/seed /seed
ENV SEED_DIR=/seed
CMD ["python", "-u", "worker.py"]
