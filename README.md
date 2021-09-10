# Ergo Blockchain Scanner

## Motivation

Currently, there are two main ways to extract data from the Ergo blockchain 
for applications, whether using node with custom scans (ergoutils, Ergo Auctions, SigmaUSD UI)  
or explorer API (ErgoMixer). 

However, both node and explorer APIs has limitations, also, can have problems under high load.

Thus this scanner is providing a way to scan and extract boxes and corresponding 
transactions block-by-block, with forks handling, database storage, configurable 
database schema and blockchain scanning rules.


## TODO: 

* Swagger or other api documentations
* DockerFile
* Add api-key for some routes
* Update readme   
