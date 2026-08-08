FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

# Shared with model-inference through the `model-artifacts` volume: a run
# writes /app/model/<version>/ here, and the inference container reloads it
# from the same volume when an admin promotes that version (§12.4).
ENV MODEL_ROOT=/app/model
# Scratch space for Trainer checkpoints and any downloaded dataset file.
ENV DATA_ROOT=/app/data

EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0) if urllib.request.urlopen('http://localhost:8001/healthz').status==200 else sys.exit(1)"

# One worker on purpose: a fine-tune saturates the CPU, and a second worker
# would fight it for cores while sharing the same artifact volume.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1"]
