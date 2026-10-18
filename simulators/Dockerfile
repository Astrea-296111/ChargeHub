FROM python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1
WORKDIR /simulators
COPY common.py payment.py device.py ./
ENV PYTHONUNBUFFERED=1 SIMULATOR_HOST=0.0.0.0
CMD ["python","payment.py"]
