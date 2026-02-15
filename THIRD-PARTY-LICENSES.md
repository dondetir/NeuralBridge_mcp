# Third-Party Licenses

NeuralBridge uses the following open source software:

## Rust Dependencies (MCP Server)

### Runtime Dependencies

| Package | License | URL |
|---------|---------|-----|
| **rmcp** (0.15) | MIT | https://github.com/modelcontextprotocol/rust-sdk |
| **schemars** (1.0) | MIT | https://github.com/GREsau/schemars |
| **tokio** (1.35) | MIT | https://github.com/tokio-rs/tokio |
| **prost** (0.12) | Apache-2.0 | https://github.com/tokio-rs/prost |
| **prost-types** (0.12) | Apache-2.0 | https://github.com/tokio-rs/prost |
| **bytes** (1.5) | MIT | https://github.com/tokio-rs/bytes |
| **anyhow** (1.0) | MIT OR Apache-2.0 | https://github.com/dtolnay/anyhow |
| **thiserror** (1.0) | MIT OR Apache-2.0 | https://github.com/dtolnay/thiserror |
| **tracing** (0.1) | MIT | https://github.com/tokio-rs/tracing |
| **tracing-subscriber** (0.3) | MIT | https://github.com/tokio-rs/tracing |
| **serde** (1.0) | MIT OR Apache-2.0 | https://github.com/serde-rs/serde |
| **serde_json** (1.0) | MIT OR Apache-2.0 | https://github.com/serde-rs/json |
| **futures** (0.3) | MIT OR Apache-2.0 | https://github.com/rust-lang/futures-rs |
| **async-trait** (0.1) | MIT OR Apache-2.0 | https://github.com/dtolnay/async-trait |
| **uuid** (1.6) | MIT OR Apache-2.0 | https://github.com/uuid-rs/uuid |
| **base64** (0.22) | MIT OR Apache-2.0 | https://github.com/marshallpierce/rust-base64 |
| **image** (0.25) | MIT OR Apache-2.0 | https://github.com/image-rs/image |

### Build Dependencies

| Package | License | URL |
|---------|---------|-----|
| **prost-build** (0.12) | Apache-2.0 | https://github.com/tokio-rs/prost |

## Android Dependencies (Companion App)

### Kotlin Core

| Package | License | URL |
|---------|---------|-----|
| **kotlin-stdlib** (2.0.21) | Apache-2.0 | https://kotlinlang.org |
| **kotlinx-coroutines-android** (1.9.0) | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |

### AndroidX Libraries

| Package | License | URL |
|---------|---------|-----|
| **androidx.core:core-ktx** (1.12.0) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| **androidx.appcompat:appcompat** (1.6.1) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| **androidx.recyclerview:recyclerview** (1.3.2) | Apache-2.0 | https://developer.android.com/jetpack/androidx |

### Material Design

| Package | License | URL |
|---------|---------|-----|
| **com.google.android.material:material** (1.12.0) | Apache-2.0 | https://github.com/material-components/material-components-android |

### Protobuf

| Package | License | URL |
|---------|---------|-----|
| **protobuf-kotlin** (3.24.0) | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| **protobuf-java** (3.24.0) | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |

### Testing Libraries

| Package | License | URL |
|---------|---------|-----|
| **junit** (4.13.2) | EPL-1.0 | https://junit.org/junit4 |
| **androidx.test.ext:junit** (1.1.5) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| **androidx.test.espresso:espresso-core** (3.5.1) | Apache-2.0 | https://developer.android.com/training/testing/espresso |

## Native Dependencies

### C++ Libraries

| Package | License | URL |
|---------|---------|-----|
| **libjpeg-turbo** | BSD-3-Clause, IJG, zlib | https://libjpeg-turbo.org |

---

## License Texts

### MIT License
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### Apache License 2.0
Full text: http://www.apache.org/licenses/LICENSE-2.0

### BSD-3-Clause License
Full text: https://opensource.org/licenses/BSD-3-Clause

### Eclipse Public License 1.0 (EPL-1.0)
Full text: https://www.eclipse.org/legal/epl-v10.html

---

*This file is automatically maintained. If you add new dependencies, please update this file accordingly.*
