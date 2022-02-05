wrk -t 4 -c 100 -d 10s --latency "http://localhost:8080/UTC/now"
