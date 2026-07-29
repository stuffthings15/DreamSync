# DreamSync USB Device Lab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repeatable two-lane phone/Watch Ultra test and evidence system.

**Architecture:** The phone connects through USB ADB and the watch through paired
wireless ADB. Coexisting debug packages support rapid iteration; Play-signed Internal
packages remain the authority for Samsung package/signature validation. Raw evidence
is stored locally in AI Brain and only sanitized summaries are eligible for Git.

**Tech Stack:** Android/Java, Wear OS Data Layer, Gradle, PowerShell, ADB.

## Global Constraints

- Wrist-sensor outputs are probable control states, not EEG/AASM staging.
- No destructive, overnight, or controlled-cue scenario runs without its explicit switch.
- Raw device evidence and identifiers are never committed.
- Debug-signature Samsung results are diagnostic only.

---

### Task 1: Safe debug identity

- [x] Add matching `.debug` application ID and `DreamSync Dev` label to both apps.
- [x] Test package/lane selection and build both debug APKs.

### Task 2: Device-lab commands

- [x] Add connection, capture, and analysis entry points.
- [x] Add device classification, redaction, clock correlation, scenario gates, and mixed-lane rejection.
- [x] Add classified findings and checksummed AI Brain evidence output.

### Task 3: Bound watch snapshot

- [x] Add bounded snapshot request/response protocol constants.
- [x] Validate the request and response against the durable phone/watch binding.
- [x] Redact identifiers and exclude raw PPG.
- [x] Include the fresh snapshot in the user-triggered phone debug export.

### Task 4: Documentation and verification

- [x] Document connection, lanes, safety gates, evidence, and recovery.
- [x] Run the full unit/lint/build verification.
- [ ] Connect a Watch Ultra and execute the first five-minute awake system check.
