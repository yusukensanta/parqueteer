.PHONY: help install sync-versions clean compile test assembly package run fmt lint check setup-dev setup-hooks

# Default target: show help
.DEFAULT_GOAL := help

# Detect if mise is available
MISE := $(shell command -v mise 2> /dev/null)

# Colors for output
BLUE := \033[0;34m
GREEN := \033[0;32m
YELLOW := \033[0;33m
NC := \033[0m # No Color

##@ General

help: ## Display this help message
	@echo "$(BLUE)parqueteer Development Commands$(NC)"
	@echo ""
	@awk 'BEGIN {FS = ":.*##"; printf "Usage:\n  make $(GREEN)<target>$(NC)\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  $(GREEN)%-20s$(NC) %s\n", $$1, $$2 } /^##@/ { printf "\n$(YELLOW)%s$(NC)\n", substr($$0, 5) } ' $(MAKEFILE_LIST)

##@ Setup

install: ## Install project dependencies
ifdef MISE
	@echo "$(GREEN)✓$(NC) mise detected - installing tools from .tool-versions"
	mise install
else
	@echo "$(YELLOW)⚠$(NC)  mise not found - using system sbt"
	@echo "$(BLUE)ℹ$(NC)  For automatic version management, install mise: https://mise.jdx.dev"
endif

sync-versions: ## Sync sbt version from .tool-versions to build.properties
ifdef MISE
	@echo "$(GREEN)✓$(NC) Syncing sbt version..."
	@./scripts/sync-sbt-version.sh
else
	@echo "$(YELLOW)⚠$(NC)  mise not found - skipping version sync"
	@echo "$(BLUE)ℹ$(NC)  Using sbt version from project/build.properties"
endif

setup-dev: install setup-hooks ## Complete development environment setup
	@echo "$(GREEN)✓$(NC) Development environment ready!"
	@echo ""
	@echo "Next steps:"
	@echo "  make compile    # Compile the project"
	@echo "  make test       # Run tests"
	@echo "  make assembly   # Create distributable JAR"

setup-hooks: ## Configure git hooks for version sync
	@echo "$(GREEN)✓$(NC) Setting up git hooks..."
	@chmod +x .githooks/*
	@git config core.hooksPath .githooks
	@echo "$(GREEN)✓$(NC) Git hooks configured"

##@ Build

compile: sync-versions ## Compile the project
	@echo "$(BLUE)Building parqueteer...$(NC)"
	sbt compile

test: sync-versions ## Run all tests
	@echo "$(BLUE)Running tests...$(NC)"
	sbt test

assembly: sync-versions ## Create fat JAR with all dependencies
	@echo "$(BLUE)Creating assembly JAR...$(NC)"
	sbt assembly
	@echo "$(GREEN)✓$(NC) JAR created at: target/scala-3.7.3/parqueteer.jar"

package: sync-versions ## Create distribution package (with launch scripts)
	@echo "$(BLUE)Creating distribution package...$(NC)"
	sbt universal:packageBin
	@echo "$(GREEN)✓$(NC) Package created in: target/universal/"

stage: sync-versions ## Create staged distribution for testing
	@echo "$(BLUE)Staging distribution...$(NC)"
	sbt universal:stage
	@echo "$(GREEN)✓$(NC) Staged at: target/universal/stage/"
	@echo "$(BLUE)ℹ$(NC)  Run: ./target/universal/stage/bin/parqueteer --help"

##@ Development

run: compile ## Run the application (usage: make run ARGS="--help")
	@echo "$(BLUE)Running parqueteer...$(NC)"
	sbt "run $(ARGS)"

fmt: ## Format code with scalafmt
	@echo "$(BLUE)Formatting code...$(NC)"
	sbt scalafmtAll

lint: ## Check code style
	@echo "$(BLUE)Linting code...$(NC)"
	sbt scalafmtCheckAll

check: lint test ## Run all checks (lint + test)
	@echo "$(GREEN)✓$(NC) All checks passed!"

##@ Clean

clean: ## Remove build artifacts
	@echo "$(BLUE)Cleaning build artifacts...$(NC)"
	sbt clean
	@rm -rf target/
	@echo "$(GREEN)✓$(NC) Clean complete"

clean-all: clean ## Remove all generated files including IDE files
	@echo "$(BLUE)Deep cleaning...$(NC)"
	@rm -rf .bloop .metals .bsp project/target project/project/target
	@echo "$(GREEN)✓$(NC) Deep clean complete"

##@ Release

version: ## Show current version
	@sbt "show version"

publish-local: assembly ## Publish JAR to local Maven repository
	@echo "$(BLUE)Publishing to local repository...$(NC)"
	sbt publishLocal

##@ CI/CD

ci-test: ## CI test pipeline (no version sync)
	@echo "$(BLUE)Running CI tests...$(NC)"
	sbt clean compile test

ci-build: ## CI build pipeline (no version sync)
	@echo "$(BLUE)Running CI build...$(NC)"
	sbt clean assembly

##@ Information

show-versions: ## Show all tool versions
	@echo "$(BLUE)Tool Versions:$(NC)"
	@echo ""
	@echo "Java:"
	@java -version 2>&1 | head -1
	@echo ""
	@echo "sbt (from build.properties):"
	@cat project/build.properties
	@echo ""
	@echo "Scala (from build.sbt):"
	@grep scalaVersion build.sbt | head -1 || echo "Not found"
ifdef MISE
	@echo ""
	@echo "mise (from .tool-versions):"
	@cat .tool-versions
endif

show-tasks: ## Show available sbt tasks
	@echo "$(BLUE)Available sbt tasks:$(NC)"
	@sbt tasks | grep -E "^  [a-z]" | head -20
