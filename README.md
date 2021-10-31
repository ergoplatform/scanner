# Ergo Blockchain Scanner

## Motivation

Currently, there are two main ways to extract data from the Ergo blockchain 
for applications, whether using node with custom scans (ergoutils, Ergo Auctions, SigmaUSD UI)  
or explorer API (ErgoMixer). 

However, both node and explorer APIs has limitations, also, can have problems under high load.

Thus this scanner is providing a way to scan and extract boxes and corresponding 
transactions block-by-block, with forks handling, database storage, configurable 
database schema and blockchain scanning rules.

## Docker Quick Start
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


## REST API

The REST API to the scanner is described below.

## Get status of Scanner

### Request

`GET /info`

    curl -i -H 'Accept: application/json' http://localhost:9000/info

### Response

    HTTP/1.1 200 OK
    Date: Thu, 24 Feb 2011 12:36:30 GMT
    Content-Type: application/json
    Content-Length: 62

    {
        "lastScannedHeight" : 554437,
        "networkHeight" : 609429
    }

## Register a new Scan

### Request

`POST /scan/register`

    curl -X POST 'localhost:9000/scan/register' -H 'Content-Type: application/json' -i --data-raw '{
        "scanName": "ControlBox NFT",
        "trackingRule": {
            "predicate": "containsAsset",
            "assetId": "72c3fbce3243d491d81eb564cdab1662b1f8d4c7e312b88870cec79b7cfd4321"
        }
    }'

### Response

    HTTP/1.1 200 OK
    Date: Thu, 24 Feb 2011 12:36:30 GMT
    Content-Type: application/json
    Content-Length: 19

    {
        "scanId" : 1
    }

## Deregister a Scan

### Request
`POST /scan/deregister`

    curl -X POST 'localhost:9000/scan/deregister' -H 'Content-Type: application/json' -i -d '{
        "scanId": 1
    }'

### Response

    HTTP/1.1 200 OK
    Date: Thu, 24 Feb 2011 12:36:30 GMT
    Content-Type: application/json
    Content-Length: 19

    {
        "scanId" : 1
    }

## Get list of Scans

### Request

`GET /scan/listAll`

    curl -i -H 'Accept: application/json' http://localhost:9000/scan/listAll

### Response

    HTTP/1.1 200 OK
    Date: Thu, 24 Feb 2011 12:36:30 GMT
    Content-Type: application/json
    Content-Length: 229

    [
        {
            "scanId" : 1,
            "scanName" : "ControlBox NFT",
            "trackingRule" : {
            "predicate" : "containsAsset",
            "assetId" : "72c3fbce3243d491d81eb564cdab1662b1f8d4c7e312b88870cec79b7cfd4321"
            }
        }
    ]

## Get list of unspent Boxes of scanId

### Request

`GET /scan/unspentBoxes/:scanId?minConfirmations=Int&minInclusionHeight=Int`

    curl -i -H 'Accept: application/json' http://localhost:9000/scan/unspentBoxes/1?minConfirmations=1000&minInclusionHeight=500000

### Response

    HTTP/1.1 200 OK
    Date: Thu, 24 Feb 2011 12:36:30 GMT
    Content-Type: application/json
    Content-Length: 589

    [
        {
            "boxId" : "8bce771e080fbd054a70ec387106d4f6d9eeb4d9cd4b47e1014bda76619f8631",
            "value" : 100000000,
            "ergoTree" : "10010100d17300",
            "assets" : [
            {
                "tokenId" : "72c3fbce3243d491d81eb564cdab1662b1f8d4c7e312b88870cec79b7cfd4321",
                "amount" : 1
            }
            ],
            "creationHeight" : 553058,
            "additionalRegisters" : {
            "R4" : "058084af5f",
            "R5" : "08cd0327e65711a59378c59359c3e1d0f7abe906479eccb76094e50fe79d743ccc15e6"
            },
            "transactionId" : "48b5858414bd3950b66036b2fed9ad4d2f3e8033d78056aab0affffbf0812ae6",
            "index" : 0
        }
    ]
