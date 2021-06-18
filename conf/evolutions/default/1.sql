-- Create schema
-- !Ups
CREATE TABLE headers
(
    id                VARCHAR(64) NOT NULL,
    parent_id         VARCHAR(64) NOT NULL,
    height            INTEGER     NOT NULL,
    timestamp         BIGINT      NOT NULL,
    main_chain        BOOLEAN     NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX "headers__parent_id" ON headers (parent_id);
CREATE INDEX "headers__height" ON headers (height);
CREATE INDEX "headers__ts" ON headers (timestamp);
CREATE INDEX "headers__main_chain" ON headers (main_chain);

CREATE TABLE transactions
(
    id               VARCHAR(64) NOT NULL,
    header_id        VARCHAR(64) REFERENCES headers (id),
    inclusion_height INTEGER     NOT NULL,
    timestamp        BIGINT      NOT NULL,
    main_chain       BOOLEAN     NOT NULL,
    PRIMARY KEY (id, header_id)
);

CREATE INDEX "transactions__header_id" ON transactions (header_id);
CREATE INDEX "transactions__timestamp" ON transactions (timestamp);
CREATE INDEX "transactions__inclusion_height" ON transactions (inclusion_height);
CREATE INDEX "transactions__main_chain" ON transactions (main_chain);


CREATE TABLE inputs
(
    box_id      VARCHAR(64) NOT NULL,
    tx_id       VARCHAR(64) NOT NULL,
    header_id   VARCHAR(64) NOT NULL,
    proof_bytes VARCHAR,
    index       INTEGER     NOT NULL,
    main_chain  BOOLEAN     NOT NULL,
    PRIMARY KEY (box_id, header_id)
);

CREATE INDEX "inputs__tx_id" ON inputs (tx_id);
CREATE INDEX "inputs__box_id" ON inputs (box_id);
CREATE INDEX "inputs__header_id" ON inputs (header_id);
CREATE INDEX "inputs__main_chain" ON inputs (main_chain);

CREATE TABLE data_inputs
(
    box_id      VARCHAR(64) NOT NULL,
    tx_id       VARCHAR(64) NOT NULL,
    header_id   VARCHAR(64) NOT NULL,
    index       INTEGER     NOT NULL,
    main_chain  BOOLEAN     NOT NULL,
    PRIMARY KEY (box_id, tx_id, header_id)
);

CREATE INDEX "data_inputs__tx_id" ON data_inputs (tx_id);
CREATE INDEX "data_inputs__box_id" ON data_inputs (box_id);
CREATE INDEX "data_inputs__header_id" ON data_inputs (header_id);
CREATE INDEX "data_inputs__main_chain" ON data_inputs (main_chain);


CREATE TABLE outputs
(
    box_id                  VARCHAR(64) NOT NULL,
    tx_id                   VARCHAR(64) NOT NULL,
    header_id               VARCHAR(64) NOT NULL,
    value                   BIGINT      NOT NULL,
    creation_height         INTEGER     NOT NULL,
    index                   INTEGER     NOT NULL,
    ergo_tree               VARCHAR     NOT NULL,
    timestamp               BIGINT      NOT NULL,
    main_chain              BOOLEAN     NOT NULL,
    PRIMARY KEY (box_id, header_id)
);

CREATE INDEX "outputs__box_id" ON outputs (box_id);
CREATE INDEX "outputs__tx_id" ON outputs (tx_id);
CREATE INDEX "outputs__header_id" ON outputs (header_id);
CREATE INDEX "outputs__ergo_tree" ON outputs (ergo_tree);
CREATE INDEX "outputs__timestamp" ON outputs (timestamp);
CREATE INDEX "outputs__main_chain" ON outputs (main_chain);

CREATE TABLE assets
(
    token_id  VARCHAR(64) NOT NULL,
    box_id    VARCHAR(64) NOT NULL,
    header_id VARCHAR(64) NOT NULL,
    index     INTEGER     NOT NULL,
    value     BIGINT      NOT NULL,
    PRIMARY KEY (index, token_id, box_id, header_id)
);

CREATE INDEX "assets__box_id" ON assets (box_id);
CREATE INDEX "assets__token_id" ON assets (token_id);
CREATE INDEX "assets__header_id" ON assets (header_id);

CREATE TABLE box_registers
(
    id               VARCHAR(2)    NOT NULL,
    box_id           VARCHAR(64)   NOT NULL,
    value            VARCHAR(8192) NOT NULL,
    PRIMARY KEY (id, box_id)
);

CREATE INDEX "box_registers__id" ON box_registers (id);
CREATE INDEX "box_registers__box_id" ON box_registers (box_id);

-- !Down
