# Contributing to Reverse Proxy Rate Limiter

Thank you for your interest in contributing!

## Branching and Protection Rules

The default branch (`main`) is protected with the following enforcement rules:
- **Pull Requests Required**: Direct pushes to `main` are restricted. All changes must be submitted via feature/fix branches through pull requests.
- **Passing Status Checks Required**: Every pull request must pass the automated GitHub Actions CI suite (`Build, Lint & Test (Java 21)`) before merging.
- **Linear History & No Force Pushes**: Force pushes and branch deletions on `main` are strictly blocked.
- **Code Coverage Standards**: Pull requests must maintain $\ge 80\%$ test coverage on core rate limiting components (`com.kanak.ratelimiter.core.*`), enforced by JaCoCo.

## Development Workflow

1. Clone the repository and create a descriptive branch:
   ```bash
   git checkout -b feat/your-feature-name
   ```
2. Build and verify test suites:
   ```bash
   mvn clean verify
   ```
3. Run microbenchmarks locally to ensure zero latency regressions:
   ```bash
   java -jar target/reverse-proxy-rate-limiter-1.0.0.jar bench-memory --requests 100000 --threads 4
   ```
4. Commit your changes with clear, semantic commit messages.
5. Push to your branch and open a Pull Request against `main`.
