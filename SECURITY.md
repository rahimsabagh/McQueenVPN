# Security Policy

## Supported Versions

McQueen VPN is currently in beta.

Security fixes are developed for the latest available release. Users are strongly encouraged to upgrade to the latest release before reporting an issue or requesting a security fix.

| Version | Security Support |
|---|---|
| Latest release | Supported |
| Older releases | Not guaranteed |
| Development builds | Not guaranteed |

Security support for beta releases may change as the project evolves.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull requests.**

If you discover a potential security vulnerability, report it privately using GitHub's **Report a vulnerability** feature on this repository's Security tab.

If private vulnerability reporting is unavailable, contact the repository maintainer through their GitHub profile. Do not publish vulnerability details while waiting for a response.

When submitting a report, include as much of the following information as possible:

- A clear description of the vulnerability.
- The affected McQueen VPN version, Android version, and device architecture, if relevant.
- The affected component or file, if known.
- Steps to reproduce the issue.
- The potential security impact.
- Proof-of-concept code or logs, provided they do not expose real credentials or personal data.

Please allow the maintainer reasonable time to investigate and address the issue before publicly disclosing it.

## Scope

Security reports relevant to this project include, but are not limited to:

- Authentication, authorization, or credential-handling weaknesses.
- Exposure or insecure storage of VPN credentials, tokens, or private configuration data.
- Insecure handling of remote server manifests, including `servers.json`.
- Network security issues, including unintended cleartext communication or TLS validation weaknesses.
- Vulnerabilities in tunnel establishment, routing, or connection management.
- Android application vulnerabilities that could expose user data or compromise device security.
- Vulnerable dependencies or unsafe integration of third-party components.

## Out of Scope

The following are generally outside the direct security scope of this repository:

- Vulnerabilities in VPN servers, hosting providers, or infrastructure not operated or controlled by the McQueen VPN maintainers.
- Security issues in third-party projects such as Xray-core, Aether, or other upstream dependencies that are not caused by McQueen-specific integration.
- General feature requests, usability issues, or non-security bugs.
- Reports requiring physical access to a device or an already-compromised operating system, unless they demonstrate an additional security impact.

For vulnerabilities originating in third-party dependencies, please also report the issue to the relevant upstream project.

## Responsible Disclosure

Please act in good faith when researching and reporting security issues.

- Do not access, modify, or delete data belonging to other users.
- Do not disrupt VPN services, infrastructure, or other systems.
- Do not exploit a vulnerability beyond what is necessary to demonstrate its impact.
- Do not publicly disclose vulnerability details before coordinating with the maintainer.
- Do not include real VPN credentials, private keys, access tokens, or personal information in reports.

## Security Considerations

McQueen VPN integrates third-party networking and tunneling components. The security of an active VPN connection may also depend on the selected server, upstream software, network environment, and remote infrastructure.

A successful connection does not, by itself, guarantee anonymity, complete privacy, or protection against every form of network surveillance.

Users should only use server configurations and remote manifest sources they trust. Custom manifest URLs and associated access tokens should be handled as sensitive information.

## Response and Remediation

The maintainer will review vulnerability reports and assess their severity, reproducibility, and impact.

Confirmed vulnerabilities may be addressed through application updates, configuration changes, dependency updates, or other appropriate mitigations.

There is currently no guaranteed response or remediation timeline. The availability and timing of fixes depend on the severity and complexity of each report.

Thank you for helping keep McQueen VPN and its users safe.
