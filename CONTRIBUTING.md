# Contributing to NeuralBridge

Thank you for your interest in contributing to NeuralBridge! This document provides guidelines for contributing to the project.

## 🎯 Project Vision

NeuralBridge enables AI agents to control Android devices with <100ms latency using the Model Context Protocol (MCP). Every contribution should align with this core mission.

## 🚀 Quick Start

1. **Fork the repository**
2. **Clone your fork:**
   ```bash
   git clone git@github.com:YOUR_USERNAME/neuralBridge.git
   cd neuralBridge
   ```
3. **Set up development environment** (see [README](README.md#getting-started))
4. **Create a feature branch:**
   ```bash
   git checkout -b feature/your-feature-name
   ```

## 📝 Development Guidelines

### Code Style

**Kotlin (android):**
- Follow Android Kotlin style guide
- Use coroutines for async operations
- Keep AccessibilityService code efficient (<100ms target)
- Add unit tests for business logic

### Commit Messages

Use clear, descriptive commit messages:
```
Add android_pinch tool for zoom gestures

- Implement pinch-in and pinch-out via dispatchGesture
- Add scale parameter (>1.0 = zoom in, <1.0 = zoom out)
- Add tests for pinch gesture validation
- Update MCP tool documentation
```

### Testing

- All new features must include tests
- Android: `./gradlew test` in android/
- Integration: Test with real device/emulator

## 🔧 Areas for Contribution

### High Priority
- Additional MCP tools (WebView, accessibility actions)
- Performance optimizations
- Cross-platform support (iOS eventually)
- Documentation improvements

### Medium Priority
- Example scenarios and demos
- Error handling improvements
- Logging and debugging tools

### Nice to Have
- Visual diff testing
- Multi-device orchestration
- Alternative transport protocols

## 🐛 Reporting Issues

**Before submitting an issue:**
1. Check existing issues for duplicates
2. Verify you're using the latest code
3. Test with a clean build

**Include in your issue:**
- NeuralBridge version
- Android device/emulator details (API level, manufacturer)
- Steps to reproduce
- Logs (`adb logcat -s NeuralBridge:V`)

## 🔍 Pull Request Process

1. **Create a feature branch** from `main`
2. **Make your changes** following style guidelines
3. **Add/update tests** for your changes
4. **Update documentation** (README, code comments)
5. **Ensure all tests pass:**
   ```bash
   # Android tests
   cd android && ./gradlew test
   ```
6. **Commit with clear messages**
7. **Push to your fork:**
   ```bash
   git push origin feature/your-feature-name
   ```
8. **Open a Pull Request** with:
   - Clear title and description
   - Link to related issues
   - Screenshots/videos for UI changes
   - Test results/logs

### PR Review Checklist

Before requesting review, verify:
- [ ] All tests pass
- [ ] Code follows style guidelines
- [ ] Documentation is updated
- [ ] No build warnings
- [ ] Performance impact considered (<100ms latency requirement)
- [ ] Works on real Android device (not just emulator)

## 📄 License

By contributing to NeuralBridge, you agree that your contributions will be licensed under the Apache 2.0 License.

## 🤝 Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Help others learn and grow
- Keep discussions technical and on-topic

## 💬 Getting Help

- **Documentation:** See the [README](README.md) for setup and usage
- **Companion App:** See [android/README.md](android/README.md) for Android setup
- **Questions:** Open a GitHub Discussion
- **Bugs:** Open a GitHub Issue

## 🙏 Attribution

If you use NeuralBridge's ideas, architecture, or code, you must credit the repo:
- A link to https://github.com/dondetir/NeuralBridge_mcp
- A note like "Built with/inspired by NeuralBridge"

---

**Thank you for contributing to NeuralBridge!** 🎉
