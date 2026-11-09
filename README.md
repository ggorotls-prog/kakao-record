# 카톡 기록 (kakao-record)

카카오톡 알림을 읽어(안드로이드 「알림 읽기」) 방·보낸 사람·내용·시각을 슈파베이스 「룡룡룡」 `kakao_messages`에 모은다. 카톡에 접속하거나 알림을 지우지 않는다. 읽기만.

- 1:1·단톡 모두 기본 켜짐(빼고 싶은 방만 앱에서 끔). 안 받을 방은 카톡에서 알림을 끈다(2026-11-09 룡). 방 목록·스위치는 폰 안에만 있다.
- 단톡방 이름 = 카톡 방 이름 그대로(알림의 대화 바로가기 이름표). 단톡방에 사람 이름은 안 쓴다. 못 읽으면 「이름 모르는 단톡방」.
- 인터넷이 끊기면 폰에 모아 두었다가 보낸다. 같은 메시지는 한 번만(폰·서버 둘 다 열쇠로 거름).
- 서버: 표는 잠겨 있고(RLS, 정책 없음) 폰은 `kakao_register`·`kakao_add` 함수로만 넣는다. 폰마다 열쇠를 만들어 해시만 `kakao_devices`에 두고, Claude가 `approved=true`로 바꿔야 저장된다.
- 빌드: GitHub Actions `.github/workflows/android.yml` → `app/kakao-record.apk` + `app/version.json`(앱이 보고 스스로 업데이트). 서명은 기록 앱 공용 열쇠 `record`.
- 받기: https://ggorotls-prog.github.io/kakao-record/
