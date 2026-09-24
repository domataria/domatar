-- Domatar MySQL 8 schema (this realisation). Tables only; /Setup creates
-- the provider account and hosts. Loaded by /docker-entrypoint-initdb.d
-- on first database boot (MYSQL_DATABASE=domatar).
--
-- act.UsrId is the primary key so one MySQL can hold one row per login
-- identity (same ActId, different UsrIds) when several central hosts
-- share a database in the local simulation.

CREATE DATABASE IF NOT EXISTS `domatar`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_bin;
USE `domatar`;

DROP TABLE IF EXISTS `act`;
CREATE TABLE `act` (
  `ActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `UsrId` varchar(100) COLLATE ascii_bin NOT NULL,
  `UsrName` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `Pwd` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Ip1` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Token1` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Time1` bigint DEFAULT NULL,
  `Ip2` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Token2` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Time2` bigint DEFAULT NULL,
  `Ip3` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Token3` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Time3` bigint DEFAULT NULL,
  `Encryption` int DEFAULT NULL,
  `FpVersion` int NOT NULL DEFAULT 1
    COMMENT 'Fingerprint algorithm that minted ActId/OwnId. 1 = SHA-256[0..24)->Base64->32ch.',
  `OwnPrvKey` varchar(200) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
    COMMENT 'AES-256-GCM sealed Ed25519 OWNERSHIP private key. Genesis private key is not stored.',
  `GenesisPubKey` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `OwnPubKey` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `BindingVersion` bigint DEFAULT NULL,
  `BindingNotBefore` bigint DEFAULT NULL,
  `BindingSig` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Delegation` varchar(512) DEFAULT NULL,
  `DelegSig` varchar(128) DEFAULT NULL,
  `DelegNotAfter` bigint DEFAULT NULL,
  PRIMARY KEY (`UsrId`),
  KEY `idx_actId` (`ActId`)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

DROP TABLE IF EXISTS `hst`;
CREATE TABLE `hst` (
  `HstId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `Domain` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `PrvId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `Version` bigint NOT NULL DEFAULT 1,
  `FetchedAt` bigint NOT NULL DEFAULT 0,
  `PubKey` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `RecordSig` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  PRIMARY KEY (`HstId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DROP TABLE IF EXISTS `lnk`;
CREATE TABLE `lnk` (
  `HstId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `AppId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ObjId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `LnkHstId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `LnkAppId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `LnkActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `LnkObjId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `LnkClsAppId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `LnkClsId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `LnkObjName` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `LnkObjDesc` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `TagAppId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `Tag` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `Val` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `SeqNum` bigint NOT NULL,
  PRIMARY KEY (`HstId`,`AppId`,`ActId`,`ObjId`,`TagAppId`,`Tag`,`SeqNum`,`Val`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DROP TABLE IF EXISTS `op_msg`;
DROP TABLE IF EXISTS `op_dst`;
CREATE TABLE `op_dst` (
  `HstId`           varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ContextId`       varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `DstDomId`        varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `MsgName`         varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ActId`           varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `CallerDomId`     varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `Trust`           varchar(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `VisitCount`      int NOT NULL,
  `FirstSeenAt`     bigint NOT NULL,
  `LastSeenAt`      bigint NOT NULL,
  `VisitExpiresAt`  bigint NOT NULL,
  `Attachment`      json DEFAULT NULL,
  `AttachExpiresAt` bigint DEFAULT NULL,
  PRIMARY KEY (`HstId`,`ContextId`,`DstDomId`,`MsgName`),
  KEY `op_dst_ctx` (`HstId`,`ContextId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `op_msg` (
  `HstId`      varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ContextId`  varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `SrcDomId`   varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `Seq`        int NOT NULL,
  `DstDomId`   varchar(400) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `OutMsgName` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `SentAt`     bigint NOT NULL,
  PRIMARY KEY (`HstId`,`ContextId`,`SrcDomId`,`Seq`),
  KEY `op_msg_ctx` (`HstId`,`ContextId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE `pay_bal` (
  `HstId`      varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `PayeeActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `PayerActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `Remaining`  bigint NOT NULL,
  `UpdatedAt`  bigint NOT NULL,
  PRIMARY KEY (`HstId`,`PayeeActId`,`PayerActId`),
  CONSTRAINT `pay_bal_remaining_nonneg` CHECK (`Remaining` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DROP TABLE IF EXISTS `obj`;
CREATE TABLE `obj` (
  `HstId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `AppId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ActId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ObjId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `ClsAppId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `ClsId` varchar(100) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `ObjName` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `ObjDesc` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `Attrs` json DEFAULT NULL,
  PRIMARY KEY (`HstId`,`AppId`,`ActId`,`ObjId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
