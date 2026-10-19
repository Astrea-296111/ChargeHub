FROM python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1
WORKDIR /proxy
COPY --chmod=0444 scrape_proxy.py ./
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
HEALTHCHECK --interval=15s --timeout=4s --retries=3 CMD ["python", "-c", "import urllib.request;urllib.request.urlopen('http://127.0.0.1:9101/health',timeout=2)"]
CMD ["python", "scrape_proxy.py"]
