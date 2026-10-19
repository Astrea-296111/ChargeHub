SHELL := /bin/bash
export DOCKER_CONFIG := $(CURDIR)/.local/docker
COMPOSE := docker compose --env-file .local/dev.env
.PHONY: env build test up down demo acceptance faults test-invariants integration-test backup-check mq-up monitoring-up
env:
	python3 scripts/dev-env.py
	python3 scripts/container/maven-settings.py
build:
	./scripts/mvn.sh -B -ntp package
test:
	./scripts/mvn.sh -B -ntp verify
	python3 -m unittest discover -s simulators -p 'test_*.py' -v
up: env build
	$(COMPOSE) up -d --build
	python3 scripts/wait-ready.py
down:
	$(COMPOSE) down
demo:
	set -a; source .local/dev.env; set +a; python3 scripts/demo.py
acceptance:
	set -a; source .local/dev.env; set +a; python3 scripts/acceptance.py
test-invariants:
	python3 scripts/check-invariants.py
integration-test:
	python3 scripts/mysql-integration.py

faults:
	set -a; source .local/dev.env; set +a; python3 scripts/faults.py

backup-check:
	python3 scripts/backup-restore-check.py

mq-up: env
	CHARGEHUB_VCS_REF=$$(git rev-parse HEAD) $(COMPOSE) -f compose.yaml -f compose-mq.yaml up -d --build --scale worker=2
	python3 scripts/wait-ready.py

monitoring-up: env
	python3 scripts/monitoring/setup.py
	$(COMPOSE) -f compose.yaml -f compose-monitoring.yaml up -d --build --scale worker=2
