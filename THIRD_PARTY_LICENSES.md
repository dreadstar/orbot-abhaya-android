# Third-Party Licenses

This document contains the complete license texts for all third-party software used in orbot-abhaya-android.

## Table of Contents

- [Summary](#summary)
- [License Categories](#license-categories)
  - [LGPL-3.0 Components](#lgpl-30-components)
  - [Apache-2.0 Components](#apache-20-components)
  - [BSD-3-Clause Components](#bsd-3-clause-components)
- [Complete License Texts](#complete-license-texts)
  - [GNU Affero General Public License v3.0](#gnu-affero-general-public-license-v30)
  - [GNU Lesser General Public License v3.0](#gnu-lesser-general-public-license-v30)
  - [Apache License 2.0](#apache-license-20)
  - [BSD 3-Clause License](#bsd-3-clause-license)

---

## Summary

**orbot-abhaya-android** is licensed under **AGPLv3**.

This project uses open-source software under various licenses. All licenses are compatible with AGPLv3.

### Quick Reference

| Component | License | Type | Notice Required |
|-----------|---------|------|-----------------|
| orbot-abhaya-android (This Project) | AGPL-3.0 | Copyleft | Yes |
| Meshrabiya | LGPL-3.0 | Weak Copyleft | Yes ⚠️ |
| Orbot (Guardian Project) | BSD-3-Clause | Permissive | Yes |
| Tor Project | BSD-3-Clause | Permissive | Yes |
| Android AOSP Libraries | Apache-2.0 | Permissive | Yes |
| Kotlin | Apache-2.0 | Permissive | Yes |

---

## License Categories

### LGPL-3.0 Components

#### ⚠️ Meshrabiya - Virtual Mesh Networking Library

- **Copyright**: © UstadMobile FZ-LLC
- **License**: LGPL-3.0
- **Source**: https://github.com/UstadMobile/Meshrabiya
- **Our Fork**: https://github.com/dreadstar/Meshrabiya
- **Usage**: Dynamically linked library (Gradle dependency)

**LGPL Compliance Notice**:
- Meshrabiya is used as a library dependency, not merged into application code
- Users have the right to replace Meshrabiya with modified versions
- Our modifications to Meshrabiya are also LGPL-3.0 licensed
- See [GNU Lesser General Public License v3.0](#gnu-lesser-general-public-license-v30) below

---

### Apache-2.0 Components

#### Android Open Source Project (AOSP)

- **Copyright**: © The Android Open Source Project
- **License**: Apache-2.0
- **Source**: https://source.android.com/

**Includes**:
- AndroidX Core (androidx.core:core-ktx)
- AndroidX AppCompat (androidx.appcompat:appcompat)
- AndroidX Lifecycle (lifecycle-runtime-ktx, lifecycle-viewmodel-compose)
- Jetpack Compose (compose.ui, compose.material)
- Material Components (com.google.android.material:material)
- CameraX (camera-core, camera-camera2, camera-lifecycle, camera-view)
- Accompanist FlowLayout (com.google.accompanist:accompanist-flowlayout)

#### Kotlin Programming Language

- **Copyright**: © JetBrains s.r.o. and Kotlin Programming Language contributors
- **License**: Apache-2.0
- **Source**: https://github.com/JetBrains/kotlin

**Includes**:
- Kotlin Standard Library
- Kotlin Coroutines (kotlinx-coroutines-android)

---

### BSD-3-Clause Components

#### Orbot (Guardian Project)

- **Copyright**: © 2009-2025, Nathan Freitas, The Guardian Project
- **License**: BSD-3-Clause
- **Source**: https://github.com/guardianproject/orbot-android

This project is a fork and extension of Orbot, adding mesh networking capabilities.

#### Tor Project

- **Copyright**: © The Tor Project, Inc.
- **License**: BSD-3-Clause
- **Source**: https://www.torproject.org/

Tor is used for anonymous network communication.

---

## Complete License Texts

### GNU Affero General Public License v3.0

**This is the license for orbot-abhaya-android (this project).**

```
                    GNU AFFERO GENERAL PUBLIC LICENSE
                       Version 3, 19 November 2007

 Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[Full license text available at: https://www.gnu.org/licenses/agpl-3.0.txt]
[See LICENSE file in repository root for complete text]
```

**Key Points**:
- Strong copyleft: modifications must be shared under AGPL-3.0
- **Network copyleft**: Running modified version as network service = distribution
- Source code must be made available to users
- Patent protection included

**Official Documentation**:
- Full text: https://www.gnu.org/licenses/agpl-3.0.txt
- FAQ: https://www.gnu.org/licenses/gpl-faq.html#AGPLv3
- Why AGPL: https://www.gnu.org/licenses/why-affero-gpl.html

---

### GNU Lesser General Public License v3.0

**This license applies to Meshrabiya.**

```
                   GNU LESSER GENERAL PUBLIC LICENSE
                       Version 3, 29 June 2007

 Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[Full license text available at: https://www.gnu.org/licenses/lgpl-3.0.txt]
[See licenses/LGPL-3.0.txt for complete text]
```

**Key Points**:
- Weak copyleft: only library modifications must be LGPL-3.0
- Applications using LGPL libraries can be under different licenses
- **Dynamic linking permitted**: library can be replaced by users
- Modifications to library itself must be LGPL-3.0

**Compliance Requirements**:
1. Use library as separate module (✅ we do this via Gradle)
2. Allow users to replace library (✅ dynamic linking)
3. Provide attribution (✅ this document)
4. Make library modifications available (✅ our fork is public)

**Official Documentation**:
- Full text: https://www.gnu.org/licenses/lgpl-3.0.txt
- LGPL Guide: https://www.gnu.org/licenses/lgpl-3.0.html

---

### Apache License 2.0

**This license applies to Android libraries and Kotlin.**

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

[Full license text available at: https://www.apache.org/licenses/LICENSE-2.0.txt]
[See licenses/Apache-2.0.txt for complete text]
```

**Key Points**:
- Very permissive license
- Commercial use allowed
- Modifications allowed
- Patent grant included
- Attribution required

**Attribution** (as required by Apache-2.0):

Android Open Source Project:
```
Copyright (C) The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

Kotlin:
```
Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

**Official Documentation**:
- Full text: https://www.apache.org/licenses/LICENSE-2.0.txt
- FAQ: https://www.apache.org/foundation/license-faq.html

---

### BSD 3-Clause License

**This license applies to Orbot and Tor.**

```
BSD 3-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

**Attribution** (as required by BSD-3-Clause):

Orbot (Guardian Project):
```
Copyright (c) 2009-2025, Nathan Freitas, The Guardian Project
All rights reserved.
```

Tor Project:
```
Copyright (c) The Tor Project, Inc.
All rights reserved.
```

**Key Points**:
- Very permissive license
- Requires attribution
- No endorsement using project names
- No warranty

**Official Documentation**:
- Reference: https://opensource.org/licenses/BSD-3-Clause
- SPDX ID: BSD-3-Clause

---

## License Compatibility

All licenses used in this project are compatible with AGPLv3:

| License | Compatible with AGPL-3.0? | Notes |
|---------|---------------------------|-------|
| LGPL-3.0 | ✅ Yes | Can be used as library dependency |
| Apache-2.0 | ✅ Yes | Fully compatible |
| BSD-3-Clause | ✅ Yes | Fully compatible |

**License Compatibility Matrix**:
- **AGPL-3.0 ← LGPL-3.0**: ✅ LGPL libraries can be used in AGPL applications
- **AGPL-3.0 ← Apache-2.0**: ✅ Apache code can be relicensed as AGPL
- **AGPL-3.0 ← BSD-3-Clause**: ✅ BSD code can be relicensed as AGPL

**Reference**: [GNU License Compatibility](https://www.gnu.org/licenses/license-compatibility.html)

---

## Questions and Support

### About This Project's License
- **License**: AGPLv3
- **Repository**: https://github.com/dreadstar/orbot-abhaya-android
- **Issues**: https://github.com/dreadstar/orbot-abhaya-android/issues

### About Meshrabiya License
- **License**: LGPL-3.0
- **Upstream**: https://github.com/UstadMobile/Meshrabiya
- **Our Fork**: https://github.com/dreadstar/Meshrabiya

### General License Questions
- AGPL FAQ: https://www.gnu.org/licenses/gpl-faq.html
- LGPL FAQ: https://www.gnu.org/licenses/lgpl-3.0.html
- Choose a License: https://choosealicense.com/

---

## Document Information

- **Last Updated**: October 11, 2025
- **Applies To**: orbot-abhaya-android v1.0.0+
- **Maintained By**: Tyrone Thomas/BreakThrough Technologies

For the most current license information, see:
- LICENSE file in repository root
- NOTICE file in repository root
- In-app license viewer: Settings → About → Open Source Licenses
