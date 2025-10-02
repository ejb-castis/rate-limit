# curl test

## 헬스체크 (화이트리스트)
```shell
curl -i http://localhost:18089/health
```

# 로컬에서 metric 요청
curl -s http://localhost:18089/metrics | head -50

# metric 요청 : 원하면 포맷 명시
curl -s -H 'Accept: text/plain; version=0.0.4' http://localhost:18089/metrics | head

## 기본 API (비용 1)
```shell
curl -i -H 'X-User-Id: alice' http://localhost:18089/api/hello
```

## 무거운 API (비용 3)
```shell
curl -i -H 'X-User-Id: alice' http://localhost:18089/api/v1/heavy
```

## 429 확인(부하)
```shell
seq 300 | xargs -n1 -P20 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -H 'X-User-Id: alice' http://localhost:18089/api/hello | sort | uniq -c
```
- 의미

alice라는 유저로 300번의 API 요청을 동시에(최대 20개 병렬) 보내고,
각 HTTP 응답 코드(예: 200, 429 등)가 몇 번 나왔는지 집계합니다.

- 각 명령어 의미
>>
    seq 300

- 1부터 300까지 숫자를 출력(즉, 300번 반복)

>>    | xargs -n1 -P20 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
    -H 'X-User-Id: alice' http://localhost:18089/api/hello

- xargs가 seq의 각 숫자(총 300개)를 받아서, curl 명령을 1개씩(-n1) 실행
- 동시에 최대 20개(-P20)까지 병렬로 실행
- 각 요청마다 HTTP 헤더 X-User-Id: alice를 추가해서 /api/hello로 요청
- -s(silent), -o /dev/null(응답 내용은 버림), -w '%{http_code}\n'(HTTP 상태코드만 출력)

>>
    | sort | uniq -c

- 결과(HTTP 상태코드들)를 정렬하고, 각 코드별로 몇 번 나왔는지 개수 집계

## decide api test
```shell

curl -i \
  -H 'X-User-Id: alice' \
  -H 'X-Service-Key: test-service' \
  -H 'X-Server-Key: test-server' \
  'http://localhost:18089/decide?path=/api/v1/heavy'

curl -i -H 'X-User-Id: alice' 'http://localhost:18089/decide?path=/api/typeahead'
# 204 No Content
# X-Rate-Remaining: ...
# X-Rate-RuleId: r2@L5

# min-gap 차단
curl -i -H 'X-User-Id: alice' 'http://localhost:18089/decide?path=/api/typeahead'
curl -i -H 'X-User-Id: alice' 'http://localhost:18089/decide?path=/api%/typeahead'  # 180ms 내 재요청 → 429
# 429 Too Many Requests
# Retry-After: 1
# X-MinGap-Remaining-Millis: 137

# 토큰 버킷 차단 (capacity/refill 낮추고 병렬 다건)
seq 20 | xargs -n1 -P20 -I{} curl -s -o /dev/null -w '%{http_code}\n' -H 'X-User-Id: alice' \
  'http://localhost:18089/decide?path=/api/v1/heavy' | sort | uniq -c
```