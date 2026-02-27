## Codemagic으로 iOS 설치용 IPA 만들기

이 레포는 Flutter 프로젝트가 `color_picker_tool/` 하위에 있습니다.

### 1) Codemagic에 레포 연결
- Codemagic에서 **Bitbucket/GitHub/GitLab** 중 사용중인 저장소를 연결합니다.
- 연결 후, 브랜치를 스캔하면 레포 루트의 `codemagic.yaml`을 자동으로 인식합니다.

### 2) iOS 코드사이닝 준비(필수)
iOS에서 `.ipa`는 **서명 없이는 실기기에 설치가 불가**합니다.

Codemagic UI에서 아래를 준비/업로드하세요.
- **App Store Connect API Key(.p8)**: Team integrations → Developer Portal → Manage keys
- **iOS Certificate(.p12)**: codemagic.yaml settings → Code signing identities → iOS certificates
- **Provisioning Profile(.mobileprovision)**: codemagic.yaml settings → Code signing identities → iOS provisioning profiles

그리고 `codemagic.yaml`의 `distribution_type`에 맞는 프로파일이 있어야 합니다.
- `ad_hoc`: TestFlight 없이 배포(디바이스 등록 필요)
- `app_store`: TestFlight / App Store 배포

### 3) bundle id 확인
현재 iOS 번들ID는 다음으로 설정되어 있습니다.
- `com.atx.pic.colorPickerTool`

Apple Developer Portal / App Store Connect에서도 동일한 bundle id로 App ID가 있어야 합니다.

### 4) 워크플로우 선택
- **설치/테스트(TestFlight) IPA**: `ios_ipa_adhoc` (현재는 App Store 타입으로 설정됨)
- **App Store/TestFlight용**: `ios_ipa_app_store`

### 5) 빌드 산출물
워크플로우 실행 후 아티팩트로 아래가 생성됩니다.
- `color_picker_tool/build/ios/ipa/*.ipa`
- `color_picker_tool/build/ios/ipa/*.dSYM.zip`

### 6) 중요(프로파일 타입)
`distribution_type`에 맞는 provisioning profile이 Codemagic에 업로드/연동되어 있어야 합니다.
- `ad_hoc`: 디바이스 UDID 등록 필요
- `app_store`: TestFlight/App Store용

