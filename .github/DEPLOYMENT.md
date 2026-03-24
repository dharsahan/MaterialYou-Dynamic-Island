# GitHub Actions Setup for Material You Dynamic Island

This document explains how to configure the GitHub Actions workflows for automated building, testing, and releasing of the Material You Dynamic Island Android app.

## Required GitHub Secrets

To enable all features of the CI/CD pipeline, configure these secrets in your GitHub repository settings:

### 🔑 **Signing Secrets (Required for Release APKs)**
```
KEYSTORE_BASE64       # Base64 encoded keystore file
KEYSTORE_PASSWORD     # Password for the keystore
KEY_ALIAS            # Alias of the signing key
KEY_PASSWORD         # Password for the signing key
```

### 🔔 **Notification Secrets (Optional)**
```
DISCORD_WEBHOOK      # Discord webhook URL for release notifications
```

### 🛡️ **Code Quality Secrets (Optional)**
```
SONAR_TOKEN         # SonarCloud token for code analysis
```

## Setting Up Signing

### 1. Generate Keystore (if you don't have one)
```bash
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias release
```

### 2. Convert Keystore to Base64
```bash
# Linux/macOS
base64 -i release-key.jks | tr -d '\n'

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-key.jks"))
```

### 3. Add Secrets to GitHub
1. Go to your repository → Settings → Secrets and Variables → Actions
2. Click "New repository secret"
3. Add each secret with the exact names listed above

## Workflow Overview

### 📋 **Main Build Workflow** (`build-and-release.yml`)
**Triggers:**
- Push to `main` or `develop` branches
- Pull requests to `main`
- Git tags starting with `v*`
- Manual workflow dispatch

**Jobs:**
1. **Lint and Test** - Code quality checks and unit tests
2. **Build Debug** - Creates debug APK for PR/development builds
3. **Build Release** - Creates signed release APK for main/tags
4. **Security Scan** - Trivy vulnerability scanning
5. **Discord Notification** - Sends release notifications

### 🧪 **Android Tests Workflow** (`android-tests.yml`)
**Triggers:**
- Push to `main` or `develop` branches
- Pull requests to `main`
- Daily at 2 AM UTC
- Manual workflow dispatch

**Features:**
- Tests on Android API 31, 33, and 34
- Uses Android emulator on macOS runners
- Caches AVD snapshots for faster execution
- Uploads test reports as artifacts

### 🔒 **Dependencies & Security Workflow** (`dependencies-security.yml`)
**Triggers:**
- Weekly on Sundays at 3 AM UTC
- Changes to build files
- Manual workflow dispatch

**Features:**
- OWASP dependency vulnerability scanning
- License compliance checking
- Dependency update reports
- Code quality analysis with Detekt and SonarCloud

## Release Process

### Automatic Releases
1. **Create a tag** following semantic versioning:
   ```bash
   git tag v1.0.5
   git push origin v1.0.5
   ```
2. **GitHub Actions will:**
   - Run all tests and quality checks
   - Build signed release APK
   - Create GitHub release with APK attachment
   - Send Discord notification (if configured)

### Manual Releases
1. Go to Actions tab in GitHub
2. Select "Build and Release Android App"
3. Click "Run workflow"
4. Choose "release" type
5. Click "Run workflow"

## Artifacts and Reports

### 📱 **Build Artifacts**
- **Debug APKs**: Available for 14 days
- **Release APKs**: Available for 30 days
- **Test Results**: Available for 14 days

### 📊 **Quality Reports**
- **Lint Results**: HTML reports with code issues
- **Test Reports**: JUnit XML and HTML reports
- **Dependency Check**: OWASP vulnerability reports
- **License Report**: Dependency license compliance
- **Detekt**: Kotlin code analysis results

## Configuration Files

### Required Files Created:
- `.github/workflows/build-and-release.yml` - Main CI/CD pipeline
- `.github/workflows/android-tests.yml` - Automated testing
- `.github/workflows/dependencies-security.yml` - Security and dependencies
- `config/detekt/detekt.yml` - Detekt configuration

### Build Configuration Updates:
- Added signing configuration to `app/build.gradle`
- Added quality analysis plugins
- Enhanced dependency management

## Troubleshooting

### Common Issues:

1. **Build Fails on Signing**
   - Ensure all signing secrets are correctly set
   - Verify keystore file is properly base64 encoded

2. **Tests Timeout**
   - Android emulator tests may take 30+ minutes
   - Check AVD cache is working properly

3. **Dependency Check Fails**
   - Review OWASP report for actual vulnerabilities
   - Add suppressions to `dependency-check-suppressions.xml` if needed

4. **SonarCloud Integration**
   - Requires SONAR_TOKEN secret
   - Project must be set up in SonarCloud first

### Getting Help:
- Check Actions logs for detailed error messages
- Review artifact uploads for test results and reports
- Ensure all required secrets are properly configured

## Security Considerations

- **Never commit keystore files** to repository
- **Use GitHub Secrets** for all sensitive data
- **Review dependency reports** regularly for vulnerabilities
- **Keep dependencies updated** using automated reports
- **Monitor security scans** for new vulnerabilities

The CI/CD pipeline is designed to maintain high code quality while automating the tedious parts of Android app development and deployment.