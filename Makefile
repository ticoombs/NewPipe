# NewPipe fork — F-Droid publish helper.
#
# Upstream NewPipe is built via Gradle directly. This Makefile only adds
# `fdroid-publish` so we can ship the fork to our internal F-Droid repo
# (sibling directory ../fdroid-repo). For everything else use ./gradlew.

GRADLE := ./gradlew

# Override these on the command line if you want a different applicationId
# suffix (e.g. .featureX) — must match the metadata file in fdroid-repo/metadata/.
FDROID_REPO ?= $(CURDIR)/../fdroid-repo
FDROID_PACKAGE_SUFFIX ?= .dev
FDROID_APP_ID ?= org.schabi.newpipe$(FDROID_PACKAGE_SUFFIX)

.PHONY: help fdroid-publish build-release

help:
	@echo "NewPipe fork — F-Droid publish targets"
	@echo ""
	@echo "  make build-release        - assembleRelease with -DpackageSuffix=$(FDROID_PACKAGE_SUFFIX)"
	@echo "                              (signed if NEWPIPE_KEYSTORE env var set)"
	@echo "  make fdroid-publish       - build signed release + ingest into $(FDROID_REPO)"
	@echo ""
	@echo "Variables (override on cmdline):"
	@echo "  FDROID_PACKAGE_SUFFIX=$(FDROID_PACKAGE_SUFFIX)"
	@echo "  FDROID_APP_ID=$(FDROID_APP_ID)"
	@echo "  FDROID_REPO=$(FDROID_REPO)"

build-release:
	$(GRADLE) -DpackageSuffix=$(FDROID_PACKAGE_SUFFIX) assembleRelease

fdroid-publish:
	@if [ ! -f "$(FDROID_REPO)/keystore/newpipe.env" ]; then \
		echo "error: $(FDROID_REPO)/keystore/newpipe.env not found." >&2; \
		echo "       Run 'make init-keys' in $(FDROID_REPO) first." >&2; \
		exit 1; \
	fi
	@echo "Building signed release APK (suffix=$(FDROID_PACKAGE_SUFFIX), app_id=$(FDROID_APP_ID))..."
	set -e; \
	. "$(FDROID_REPO)/keystore/newpipe.env"; \
	NEWPIPE_KEYSTORE="$$NEWPIPE_KEYSTORE" \
	NEWPIPE_KEYSTORE_PASSWORD="$$NEWPIPE_KEYSTORE_PASSWORD" \
	NEWPIPE_KEY_ALIAS="$$NEWPIPE_KEY_ALIAS" \
	NEWPIPE_KEY_PASSWORD="$$NEWPIPE_KEY_PASSWORD" \
	$(GRADLE) -DpackageSuffix=$(FDROID_PACKAGE_SUFFIX) assembleRelease; \
	apk="app/build/outputs/apk/release/app-release.apk"; \
	if [ ! -f "$$apk" ]; then \
		echo "error: expected signed APK at $$apk not found." >&2; \
		ls -1 app/build/outputs/apk/release/ >&2; \
		exit 1; \
	fi; \
	"$(FDROID_REPO)/scripts/ingest.sh" "$(FDROID_APP_ID)" "$$apk"
	@echo "✓ Published to $(FDROID_REPO)/repo/"
