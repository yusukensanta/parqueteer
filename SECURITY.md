# Security Policy

## Supported Versions

Only the latest release on the `main` branch receives security fixes.
Older versions are not patched; please upgrade to the latest release.

| Version | Supported |
| ------- | --------- |
| latest  | ✅        |
| < latest | ❌       |

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Please report vulnerabilities by emailing:

**yusukensanta@gmail.com**

Include in your report:
- Description of the vulnerability
- Steps to reproduce (proof of concept if available)
- Affected versions
- Suggested severity (CVSS score or qualitative: low/medium/high/critical)
- Any suggested remediation

You will receive an acknowledgement within **5 business days**.
We aim to release a fix within **30 days** of confirmation, depending on severity.

## Disclosure Policy

We follow **coordinated disclosure**:

1. Reporter submits vulnerability privately.
2. We confirm and work on a fix.
3. We release the fix and publish a [GitHub Security Advisory](https://github.com/yusukensanta/parqueteer/security/advisories).
4. Reporter is credited in the advisory (unless they prefer to remain anonymous).

## Scope

This policy covers the `parqueteer` library itself.

**In scope:**
- Arbitrary code execution via malformed Parquet files
- Path traversal or SSRF when reading/writing files
- Credential leakage in log output (via `CredentialRedactor`)
- Denial-of-service via resource exhaustion when parsing untrusted input

**Out of scope:**
- Vulnerabilities in the JVM, Scala runtime, or third-party dependencies
  (report those to the respective upstream projects)
- Issues only reproducible with attacker-controlled local filesystem access

## Security Advisories

Published advisories are available at:
<https://github.com/yusukensanta/parqueteer/security/advisories>
