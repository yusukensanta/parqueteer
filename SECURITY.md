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

or privately through GitHub:
[Report a vulnerability](https://github.com/yusukensanta/parqueteer/security/advisories/new).

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

## Java Runtime

parqueteer does not bundle a JVM; it runs on the one you install. Keep it on
the latest patch release of your JDK line: **21.0.13.1 / 17.0.21.1 or later**
as of November 2026. Earlier builds trust root certificates linked to the
eMudhra intermediate-CA key compromise (CVE-2026-91403, CVE-2026-114820), so
an attacker could intercept the HTTPS connections parqueteer makes to S3, GCS
and Azure. See the [OpenJDK vulnerability advisories](https://openjdk.org/groups/vulnerability/advisories/).

CI, releases and fuzzing build on Amazon Corretto.

## Security Advisories

Published advisories are available at:
<https://github.com/yusukensanta/parqueteer/security/advisories>
