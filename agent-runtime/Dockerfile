FROM python:3.12-slim
WORKDIR /app
COPY py/aegislib /opt/aegislib
RUN pip install --no-cache-dir /opt/aegislib "fastapi>=0.115" "uvicorn>=0.32" "httpx>=0.27" "psycopg[binary]>=3.2"
COPY agent-runtime/app.py /app/app.py
EXPOSE 8090
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
