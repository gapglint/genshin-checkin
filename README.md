# genshin-checkin

원신 호요랩 출석체크를 대신 해 주는 안드로이드 앱.

## 어떻게 동작하나

1. **로그인 (한 번만)** — 앱 안의 WebView로 호요랩 출첵 페이지를 연다. 아이디·비밀번호 입력과 캡차는 사람이 한다.
   로그인 쿠키(`ltoken_v2`, `ltuid_v2`)가 생기면 앱이 자동으로 꺼내 저장하고 창을 닫는다. 개발자도구로 쿠키를 복사할 필요가 없다.
2. **자동 출첵** — 호요랩 서버의 날짜가 바뀌는 순간(UTC+8 자정 = 한국 시간 새벽 1시) 직후에 출석한다.
   - 폰 시계는 틀릴 수 있어서 쓰지 않는다. 호요랩 응답의 `Date` 헤더로 서버 시각과의 차이를 기억하고,
     출석 날짜는 info 응답의 `today`를 우선한다. 알람은 폰 시계와 무관한 부팅 후 경과 시간 기준으로 건다.
   - 0시 정각 몰림을 피해 1~5분 사이로 흩뜨린다. '알람 및 리마인더'를 허용하면 정확한 시각에, 아니면 몇 분 늦게 울린다.
   - 출석 응답에 캡차 판정이 오면 응답의 challenge로 한 번 더 시도한다.
   - 성공 응답 뒤에는 다시 조회해 실제로 반영됐는지 확인한다. 반영되지 않았으면 알린다.
   - 안전장치로 WorkManager가 6시간마다 확인한다. 재부팅·앱 업데이트 뒤에는 알람을 다시 건다.
3. **막히면 알림**
   - 로그인이 풀림 → 알림을 누르면 로그인 화면이 열린다.
   - 호요랩이 출첵에 캡차를 걸음 → 알림을 누르면 출첵 페이지가 열린다. 직접 눌러서 출첵한다.
4. **보충 출석** — 오늘 출석을 확인한 직후 하루 한 번, 놓친 날을 채울 수 있는지 본다.
   가능하면 알림을 보내고, 알림을 누르면 앱이 바로 보충한다. 보충권이 모자라면 출석 페이지의 링크 방문 임무를
   완료 처리해 보충권을 받은 뒤 보충한다. 앱 설정의 "보충 출석 알림"으로 끌 수 있다.
5. **리딤 코드 자동 입력** — 출석 뒤 하루 한 번, 새 코드를 찾아 고른 캐릭터로 입력한다.
   - 출석 보상은 계정의 모든 서버 캐릭터에게 가지만, 리딤 보상은 한 서버의 UID 하나로만 간다. 그래서 받을 캐릭터를 고른다(캐릭터가 하나면 자동).
   - 코드 출처
     1. 원신 Fandom 위키 [Promotional_Code](https://genshin-impact.fandom.com/wiki/Promotional_Code) 문서(주 출처). 지금 유효한 코드만 서버·만료일·묶음과 함께 정리돼 있어, 내 서버에서 못 쓰거나 만료된 코드는 시도하지 않는다. 하나만 쓸 수 있는 묶음 코드는 하나를 받으면 나머지를 건너뛴다.
     2. 호요랩 원신 홈의 공식 코드 목록(방송 코드 등, 있을 때만).
     3. 최근 90일 커뮤니티 글(최대 20개)의 `gift?code=` 링크. 위키에 아직 안 올라온 새 코드용이며, 이미 읽은 글은 다시 열지 않는다.
   - 위키 요청에는 호요랩 로그인 정보를 보내지 않는다.
   - 한 번 입력해 본 코드는 다시 입력하지 않는다. 코드 사이에는 6초씩 쉰다. 받으면 알린다.

비밀번호는 앱이 저장하지 않는다. 쿠키만 앱 전용 저장소에 두고, 백업·기기 이전 대상에서도 뺐다.

## 빌드

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew test assembleDebug     # 디버그
./gradlew recordPaparazziDebug   # 화면 스냅숏 갱신 (app/src/test/snapshots)
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
| `CheckIn.kt` | 실행·결과 저장·알림을 묶고, 예약(알람 + 6시간 주기) |
| `ServerClock.kt` | 호요랩 서버 시각, 출석 날짜, 다음 자정 계산 |
| `DailyAlarm.kt` | 서버 기준 자정 직후 알람, 재부팅 뒤 다시 걸기 |
| `Redeem.kt` | 리딤 코드: 캐릭터 목록, 코드 찾기(공식·커뮤니티), 입력 |
| `MakeUp.kt` | 보충 출석: 가능 여부 확인, 임무로 보충권 받기, 보충 |
| `LoginActivity.kt` | WebView 로그인·쿠키 추출, 직접 출첵 페이지 |
| `MainActivity.kt` | 화면 동작(출첵, 보충, 설정, 로그아웃) |
| `MainScreen.kt` | 메인 화면을 상태(`MainUiState`)대로 그림. 스냅숏 테스트도 이걸 쓴다 |
| `Notifier.kt` | 결과 알림(같은 날 같은 알림은 한 번만) |
| `Store.kt` | 쿠키·마지막 결과 저장 |

## 알려진 한계

- 쿠키 유효기간은 호요랩이 공개하지 않는다. 만료되면 알림이 오고, 다시 로그인하면 된다.
- 배터리 최적화 제외와 '알람 및 리마인더'를 허용하지 않으면 절전 중 출석이 몇 분 늦어질 수 있다. 앱 화면에서 바로 허용할 수 있다.
- 키 파일을 잃어버리면 업데이트 설치가 안 된다. 지우고 새 키로 설치해야 하고 로그인도 다시 해야 한다.

## 라이선스

[Apache License 2.0](LICENSE)

출석 카드의 숫자 글꼴 Marcellus는 SIL Open Font License 1.1을 따른다([licenses/Marcellus-OFL.txt](licenses/Marcellus-OFL.txt)).
