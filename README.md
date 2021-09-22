# Ergo Blockchain Scanner

## Motivation

Currently, there are two main ways to extract data from the Ergo blockchain 
for applications, whether using node with custom scans (ergoutils, Ergo Auctions, SigmaUSD UI)  
or explorer API (ErgoMixer). 

However, both node and explorer APIs has limitations, also, can have problems under high load.

Thus this scanner is providing a way to scan and extract boxes and corresponding 
transactions block-by-block, with forks handling, database storage, configurable 
database schema and blockchain scanning rules.

## Using Docker
For using docker in first step you should build image for this clone project and following commands:
```shell script
cd scanner
sudo docker build -t scanner:latest .
```
After build image, you can run scanner with following command:
```shell script
sudo docker run -d \
    -p 127.0.0.1:9000:9000 \
    -v /path/on/host/to/scanner/database:/home/ergo/database \
    scanner:latest
``` 
Also for set custom configuration, use the below command:
```shell script
sudo docker run -d \
    -p 127.0.0.1:9000:9000 \
    -v /path/on/host/to/scanner/database:/home/ergo/database \
    -v /path/on/host/system/to/application.conf:/home/ergo/application.conf
    scanner:latest
``` 

Both commands also would store database of scanner in `/path/on/host/to/scanner/database` on host system, and open ports `9000` (REST API) locally on host system. The `/path/on/host/to/scanner/database` directory must has `777` permissions or has owner/group numeric id equal to `9052` to be writable by container, as `ergo` user inside Docker image.

## TODO: 

* Update readme 
* Swagger or other api documentations
* Add api-key for some routes
