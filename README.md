# Real-Time Auction System

컴퓨터 네트워크 팀 프로젝트. Java TCP 소켓으로 여러 클라이언트가 같은 경매에 참여하고, 서버는 새 최고가·입찰 순위 변경·마감 결과를 이벤트로 전달한다.

## 개발 시작

JDK 17이 필요하다. 외부 라이브러리 없이 컴파일한다.

```sh
javac --release 17 -d out src/main/java/auction/*.java
java -cp out auction.Main
```

현재 `Main`은 개발 환경을 확인하는 진입점이다. 서버와 클라이언트는 아래 역할에 맞춰 구현한다.

| 담당 | 범위 |
| --- | --- |
| Server network | TCP 연결, 세션, 메시지 처리, 이벤트 브로드캐스트 |
| Auction logic | 입찰 처리, 마감, 잔액 보류·해제·정산 |
| Client & testing | 콘솔 입출력, 요청 ID 매칭, 다중 클라이언트 테스트 |

## 협업

- 작업별 브랜치를 만들고 `main`으로 Pull Request를 연다.
- 클라이언트와 서버가 함께 쓰는 `REQ`, `RES`, `EVT` 형식은 변경 전에 팀에서 합의한다.
- 첫 통합 목표는 두 클라이언트를 연결해 한쪽의 입찰로 다른 쪽에 `EVT NEW_BID`가 표시되게 하는 것이다.
