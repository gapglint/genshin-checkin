# genshin-checkin

원신 호요랩 출석체크를 대신 해 주는 안드로이드 앱.

## 어떻게 동작하나

1. **로그인 (한 번만)** — 앱 안의 WebView로 호요랩 출첵 페이지를 연다. 아이디·비밀번호 입력과 캡차는 사람이 한다.
   로그인 쿠키(`ltoken_v2`, `ltuid_v2`)가 생기면 앱이 자동으로 꺼내 저장하고 창을 닫는다. 개발자도구로 쿠키를 복사할 필요가 없다.
2. **자동 출첵** — WorkManager가 6시간마다 출첵 여부를 확인하고, 안 했으면 출첵한다.
   호요랩의 하루는 한국 시간 새벽 1시에 바뀌므로 늦어도 그 뒤 6시간 안에 출첵된다.
3. **막히면 알림**
   - 로그인이 풀림 → 알림을 누르면 로그인 화면이 열린다.
   - 호요랩이 출첵에 캡차를 걸음 → 알림을 누르면 출첵 페이지가 열린다. 직접 눌러서 출첵한다.

비밀번호는 앱이 저장하지 않는다. 쿠키만 앱 전용 저장소에 두고, 백업·기기 이전 대상에서도 뺐다.

## 빌드

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew test assembleDebug     # 디버그
./gradlew assembleRelease        # 서명된 릴리스 (아래 서명 키 필요)
# app/build/outputs/apk/release/app-release.apk
```

## 서명 키

업데이트 설치가 되려면 항상 같은 키로 서명해야 한다. 키 파일(`*.jks`)과 비밀번호는 **저장소에 올리지 않는다.**

- 로컬: 프로젝트 루트에 `keystore.properties`를 둔다(gitignore됨).
  ```properties
  storeFile=/절대/경로/genshin-checkin-release.jks
  storePassword=...
  keyAlias=genshin-checkin
  keyPassword=...
  ```
- GitHub Actions: 저장소 Settings → Secrets and variables → Actions에 아래 4개를 등록한다.

  | 시크릿 | 값 |
  |---|---|
  | `KEYSTORE_BASE64` | `base64 -w0 genshin-checkin-release.jks` 출력 |
  | `KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
  | `KEY_ALIAS` | `genshin-checkin` |
  | `KEY_PASSWORD` | 키 비밀번호 |

  main에 푸시하면 Actions 아티팩트로 APK가 올라간다. 릴리스는 `v0.1.0` 같은 태그를 푸시하거나, Actions → Build APK → Run workflow에서 release 칸에 태그 이름을 넣으면 Releases에 APK가 올라간다.

인증서 SHA-256: `f4b459c21d197b5d4d94d312dc7e8b5cff2e62c268175d38e8c6b88e41a63447`

## 구조

| 파일 | 역할 |
|---|---|
| `CheckInClient.kt` | 출첵 API 호출(info → sign → 보상 이름)과 응답 해석 |
| `CheckIn.kt` | 실행·결과 저장·알림을 묶고, WorkManager 예약 |
| `LoginActivity.kt` | WebView 로그인·쿠키 추출, 직접 출첵 페이지 |
| `MainActivity.kt` | 상태 표시, 지금 출첵, 로그아웃 |
| `Notifier.kt` | 결과 알림(같은 날 같은 알림은 한 번만) |
| `Store.kt` | 쿠키·마지막 결과 저장 |

## 알려진 한계

- 쿠키 유효기간은 호요랩이 공개하지 않는다. 만료되면 알림이 오고, 다시 로그인하면 된다.
- 절전 모드(Doze)에서는 예약 실행이 조금 늦어질 수 있다.
- 키 파일을 잃어버리면 업데이트 설치가 안 된다. 지우고 새 키로 설치해야 하고 로그인도 다시 해야 한다.

## 라이선스

[Apache License 2.0](LICENSE)
