-- A manager's "Special Access Required" approval opens one void or refund: its token id is
-- recorded when used, so the same token can't be used again within its few minutes.
CREATE TABLE used_approval_token (
    token_id VARCHAR(64) PRIMARY KEY,
    used_at  TIMESTAMPTZ NOT NULL
);

-- Which owed free items each paid sale used up, so a refund can give them back.
CREATE TABLE member_owed_item_use (
    order_id     BIGINT   NOT NULL REFERENCES sale_order (id),
    owed_item_id BIGINT   NOT NULL REFERENCES member_owed_item (id),
    quantity     SMALLINT NOT NULL,
    PRIMARY KEY (order_id, owed_item_id),
    CONSTRAINT member_owed_item_use_quantity_ck CHECK (quantity > 0)
);
