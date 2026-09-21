FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

# The checkpoint (~350MB) downloads from Hugging Face on first boot and lands
# in HF_HOME — mount a volume there (see docker-compose.yml) so restarts never
# re-download. For a fully offline image, pre-bake it instead:
#   RUN python -c "from transformers import AutoImageProcessor, AutoModelForImageClassification; \
#       AutoImageProcessor.from_pretrained('Falconsai/nsfw_image_detection'); \
#       AutoModelForImageClassification.from_pretrained('Falconsai/nsfw_image_detection')"
ENV HF_HOME=/app/hf-cache

EXPOSE 8002

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0) if urllib.request.urlopen('http://localhost:8002/healthz').status==200 else sys.exit(1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002", "--workers", "1"]
