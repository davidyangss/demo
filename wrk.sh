# wrk2 --version
# wrk 4.0.0 [kqueue] Copyright (C) 2012 Will Glozer
wrk2 -t 4 -c 100 -d 10s -R100 --latency "http://localhost:8080/UTC/now"
