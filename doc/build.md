# build 정보

- java 8

- gradle version: 8.13 사용
  - gradle wrapper 사용
  - 참고: gradle 9.0 은 java 17 이상이 필요함
 
- gradlew(gradle wrapper)가 없는 경우, 생성 명령어
  - local에 gradle이 설치되어있으면 생성 명령어로 wrapper를 만들 수 있음
```shell
gradle wrapper --gradle-version 8.13
```
