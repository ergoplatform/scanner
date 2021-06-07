# Ergo Blockchain Scanner

## Motivation

Currently, there are two main ways to extract data from the Ergo blockchain 
for applications, whether using node with custom scans (ergoutils, Ergo Auctions, SigmaUSD UI)  
or explorer API (ErgoMixer). 

However, both node and explorer APIs has limitations, also, can have problems under high load.

Thus this scanner is providing a way to scan and extract boxes and corresponding 
transactions block-by-block, with forks handling, database storage, configurable 
database schema and blockchain scanning rules.


**Current prototype does not processing forks yet, neither supporting database persistence,
and just extracting outputs for ErgoFund-related data** 

## TODO: 

* database persistence
* forks processing (remove data from blocks rolled back or move to dedicated tables)
* made scanning rules configurable (via config?), with customized persistence (via plugin?)
* API
* ErgoFund example   