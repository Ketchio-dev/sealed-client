> **주의 — 반드시 먼저 읽으세요.** Sealed Client는 서버 규칙을 어길 수 있는
> 기능을 포함합니다. 어떤 서버에서 무엇이 허용되는지는 서버마다 다르고 예고
> 없이 바뀝니다. 밴을 당해도 이 프로젝트는 책임지지 않습니다. 위험한 기능은
> 본인 소유의 테스트 서버에서 먼저 확인하세요.

# Sealed Client

Minecraft Java Edition용 Fabric 유틸리티 클라이언트입니다. 다른 클라이언트의
코드를 가져오지 않고 처음부터 작성했습니다.

| 지원 버전 | 필요한 Java | 상태 |
| --- | --- | --- |
| **1.21.4** | 21 | 90개 기능 전부 동작 |
| **26.2** | 25 | 90개 기능 전부 동작 |

다른 버전은 아직 지원하지 않습니다. [남은 작업량은 숫자로 적어
두었습니다](docs/DEVELOPMENT.md#다른-minecraft-버전-지원-비용).

---

## 설치

**1. Fabric을 먼저 설치하세요.**

Sealed Client는 Fabric 모드입니다. 아직 없다면 사용할 Minecraft 버전에 맞는
Fabric Loader와 Fabric API를 설치해야 합니다.

**2. JAR 하나만 넣으세요.**

`mods` 폴더에 자기 버전에 맞는 파일 **하나만** 넣습니다.

| Minecraft | 넣을 파일 |
| --- | --- |
| 1.21.4 | `sealed-client-mc1.21.4-1.0.0.jar` |
| 26.2 | `sealed-client-mc26.2-1.0.0.jar` |

둘 다 넣으면 안 됩니다.

**3. 게임을 켜고 `P`를 누르세요.**

메뉴가 열리면 설치가 끝난 것입니다.

`mods` 폴더 위치를 모르겠다면:

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

---

## 처음 켰다면

전투나 자동화 기능은 **전부 꺼진 상태로 시작합니다.** 실수로 밴당하는 일을
막기 위해서입니다. 원하는 것을 직접 켜야 합니다.

무엇을 켤지 모르겠다면 `P` 메뉴 왼쪽의 **Presets**에서 골라보세요.

| 프리셋 | 용도 |
| --- | --- |
| `Low Lag Utility` | 정보 표시 위주. 가장 안전합니다 |
| `Travel Safe` | 장거리 이동용 |
| `Crystal Practice` | 개인 연습 서버용 전투 설정 |

프리셋은 위험한 기능을 바로 켜지 않습니다. 정보성 기능만 켜고, 나머지는
5초 안에 한 번 더 확인해야 적용됩니다. 적용하다 실패하면 전부 되돌립니다.
잘못 눌렀으면 `U`로 취소하세요.

### 뭔가 잘못됐을 때

`;sealed panic`

전투·이동·자동화를 전부 끄고, 눌려 있던 키와 잡고 있던 아이템 슬롯을
놓습니다. 급할 때 이것만 기억하면 됩니다.

---

## 조작

### 기본 키

| 키 | 하는 일 |
| --- | --- |
| `P` | 메뉴 열기/닫기 |
| `H` | HUD 위치 편집 |
| `O` | 프로필 관리 |
| `K` | 프리셋 |
| `/` 또는 `Ctrl+F` | 기능 검색 |

### 메뉴 안에서

| 마우스 | 하는 일 |
| --- | --- |
| 왼쪽 클릭 | 켜기/끄기 |
| 오른쪽 클릭 | 설정 펼치기 |
| 가운데 클릭 | 즐겨찾기 |
| 휠 | 스크롤 |

### 단축키 지정

설정을 펼치면 맨 위에 `Keybind` 줄이 있습니다. 그 줄을 클릭하고 원하는 키를
누르면 됩니다. 해제는 `Esc`입니다.

채팅을 치거나 메뉴가 열려 있을 때는 단축키가 작동하지 않으니, 채팅하다가
실수로 기능이 켜지는 일은 없습니다.

---

## 명령어

`;sealed`로 시작하는 명령은 **서버 채팅으로 나가지 않습니다.** 내 클라이언트만
읽습니다.

가장 많이 쓰는 것들:

```text
;sealed help                       도움말
;sealed toggle <기능>               켜고 끄기
;sealed panic                      전부 끄기
;sealed friend add <닉네임>          친구 등록 (공격 대상에서 제외)
;sealed waypoint add <이름>          현재 위치 저장
```

<details>
<summary>전체 명령어 목록</summary>

**1.21.4**

```text
;sealed help
;sealed modules [category]
;sealed toggle <module>
;sealed bind <module> <key|none>
;sealed set <module> <setting> <value>
;sealed profile list|create|use|delete|bind
;sealed friend list|add|remove
;sealed waypoint list|add|remove
;sealed baritone status|stop
;sealed baritone goto <x> [y] <z>
;sealed baritone goto waypoint <name>
;sealed config save|reload
;sealed panic
```

**26.2**

```text
;sealed help
;sealed list [category]
;sealed status <module-id>
;sealed toggle <module-id>
;sealed friend add|remove|list [name]
;sealed waypoint add|remove|list [name]
;sealed profile list
;sealed profile save <name> [server-pattern]
;sealed profile use <name>
;sealed profile delete <name>
;sealed profile gui
;sealed hud edit|reset
;sealed baritone status|stop
;sealed baritone goto <x> <y> <z>
;sealed panic
```

</details>

---

## 기능

90개가 있습니다. 검색(`/`)으로 찾는 게 빠릅니다.

| 분류 | 예시 |
| --- | --- |
| **전투** | Auto Crystal, Kill Aura, Auto Totem, Surround, Hole Fill, Anchor/Bed Aura |
| **이동** | Elytra 보조, Step, No Fall, Safe Walk, Auto Walk, Hole Snap |
| **화면** | Player/Storage/Block ESP, Tracers, Nametags, Freecam, XRay, Chams |
| **탐색** | Waypoints, New Chunks, Logout Spots, Stash Finder, Trajectories |
| **편의** | Auto Eat/Armor/Tool/Mend, 인벤토리 정리, Auto Craft, Anti AFK |
| **HUD** | 좌표, 속도, FPS, 핑, 체력, 방어구, 레이더, 서버 TPS, 대상 정보 |

친구로 등록한 사람은 공격·조준 기능에서 **자동으로 제외**됩니다.

### 알아둘 것

**`New Chunks`가 표시하는 것.** 이번 접속에서 처음 본 청크입니다. 서버가 최근에
생성한 청크라는 뜻이 **아닙니다.** 다른 사람이 이미 다녀갔을 수도 있습니다.

**Baritone은 따로 설치해야 합니다.** 길찾기를 쓰려면 공식 Baritone을 별도로
받아 `mods`에 넣으세요. Sealed Client에 들어 있지 않고 자동으로 받지도
않습니다. 없어도 나머지 기능은 정상 작동합니다.

<details>
<summary>Baritone 설치 세부 사항</summary>

1.21.4는 공식 `baritone-api-fabric` 1.13.1이 필요합니다. API 클래스가 빠진
`standalone-fabric` 변형은 연동되지 않습니다.

`Baritone Navigator`는 기본으로 꺼져 있으며, 목적지를 정하고 `Confirm Target`을
켜야 한 번 출발합니다.

26.2는 호환 provider가 설치돼 있을 때만 연동합니다. 공식 Baritone이 26.2용을
계속 내놓는다는 보장은 없으므로, 26.2 사용자는 provider의 출처와 호환성을
직접 확인해야 합니다.

</details>

---

## 설정 파일

건드릴 일은 거의 없지만, 위치는 알아두면 좋습니다.

| 버전 | 위치 |
| --- | --- |
| 1.21.4 | `config/sealedclient/config.json` |
| 26.2 | `config/sealedclient-26.2.json` |

정상적으로 읽은 설정은 자동으로 백업됩니다. 파일이 깨져도 **설정을 잃지
않습니다** — 깨진 파일은 따로 보관하고 백업이나 기본값으로 되돌립니다.
게임이 갑자기 꺼져도 설정이 반쯤 쓰이다 마는 일이 없도록 저장합니다.

접속을 끊거나 게임을 종료하면 Sealed Client가 바꿔놓은 밝기와 화면 흔들림
설정도 원래대로 돌립니다.

---

## 안전한가

직접 만든 소스라도 실제로 돌아가는 것은 빌드된 JAR입니다. 그래서 다음을
지킵니다.

- **외부 통신 없음.** HTTP 연결, 소켓, 웹훅, 원격 업데이트를 만들지 않습니다
- **계정 정보에 접근하지 않음.** 런처 계정, 프로필, 인증 토큰을 읽지 않습니다
- **외부 프로그램을 실행하거나 코드를 내려받지 않음**
- 위 항목들을 소스 검사 테스트로 매 빌드마다 확인합니다
- 의존성은 체크섬으로 고정하고, 배포에 SBOM과 SHA-256 목록을 넣습니다

여기서 "외부 통신 없음"은 게임이 서버와 통신하지 않는다는 뜻이 아닙니다.
Sealed Client가 **Minecraft 연결과 별개의 숨은 통신 경로를 만들지 않는다**는
뜻입니다.

이것이 악성 코드가 절대 없다는 보증은 아닙니다. 가장 확실한 방법은 소스를
직접 보고 직접 빌드하는 것입니다. 자세한 내용은 [SECURITY.md](SECURITY.md)에
있습니다.

**JAR은 신뢰할 수 있는 곳에서만 받으세요.** Discord 첨부 파일이나 비공식
미러로 받은 파일은 내용이 바뀌었을 수 있습니다.

---

## 성능

전투 정확도를 "빠르다", "정확하다"로 주장하는 대신, 더 나아질 수 없는 지점을
정하고 실제 서버에서 측정했습니다.

| 항목 | 결과 |
| --- | --- |
| 반응 속도 | **1틱** (12/12 샘플) — 이보다 빠를 수 없습니다 |
| 크리스탈 위치 선택 | **최적해와 일치** (2000회 무작위 시행) |
| 폭발 피해 예측 | 10개 중 **7개 정확**, 최대 오차 0.5 |

예측이 틀릴 때는 항상 **피해를 실제보다 크게** 잡습니다. 자기 피해를
과소평가해서 죽는 방향으로는 틀리지 않습니다.

다른 클라이언트와 비교한 수치가 **아닙니다.** 이론적 상한 대비 우리 위치만
잰 것입니다. 측정 방법과 재현 명령은
[개발자 문서](docs/DEVELOPMENT.md#측정된-상한)에 있습니다.

---

## 직접 빌드하기

소스를 확인하고 직접 빌드하려면 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)를
보세요. 빌드 방법, 테스트 실행, 배포 파일 검증이 들어 있습니다.

---

## 라이선스

[Apache License 2.0](LICENSE). Minecraft, Fabric, Baritone 등은 각자의
라이선스를 따릅니다.
