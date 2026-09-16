-- V1__baseline_current_schema.sql
-- Generated from the current approved database schema on 2026-03-22.
-- Purpose: reset Flyway to a clean V1 baseline that matches the current database shape.

--
-- PostgreSQL database dump
--


-- Dumped from database version 14.22 (Homebrew)
-- Dumped by pg_dump version 18.3

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--



--
-- Name: alert_severity; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.alert_severity AS ENUM (
    'INFO',
    'WARNING',
    'CRITICAL'
);


--
-- Name: alert_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.alert_status AS ENUM (
    'OPEN',
    'CLOSED',
    'IN_PROGRESS',
    'RESOLVED'
);


--
-- Name: approval_action; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.approval_action AS ENUM (
    'APPROVE',
    'REJECT',
    'RETURN'
);


--
-- Name: audit_action; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.audit_action AS ENUM (
    'CREATE',
    'UPDATE',
    'DELETE',
    'APPROVE',
    'ARCHIVE',
    'RESTORE'
);


--
-- Name: audit_entity_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.audit_entity_type AS ENUM (
    'ORG',
    'USER',
    'CYCLE',
    'TASK',
    'INDICATOR',
    'MILESTONE',
    'REPORT',
    'ADHOC_TASK',
    'ALERT'
);


--
-- Name: indicator_level; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.indicator_level AS ENUM (
    'STRAT_TO_FUNC',
    'FUNC_TO_COLLEGE'
);


--
-- Name: indicator_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.indicator_status AS ENUM (
    'ACTIVE',
    'ARCHIVED',
    'DRAFT',
    'PENDING',
    'DISTRIBUTED',
    'PENDING_REVIEW'
);


--
-- Name: TYPE indicator_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TYPE public.indicator_status IS 'Indicator lifecycle status (指标生命周期状态):
- DRAFT (草稿): Not yet submitted for review
- PENDING_REVIEW (待审核): Submitted and awaiting strategic dept approval of indicator definition
- DISTRIBUTED (已下发): Approved and distributed to departments
- ACTIVE (运行中): Legacy status, equivalent to DISTRIBUTED (deprecated)
- ARCHIVED (已归档): Soft-deleted indicator
- PENDING (待审核): Deprecated, use PENDING_REVIEW instead

Lifecycle flow: DRAFT → PENDING_REVIEW → DISTRIBUTED → ARCHIVED

Note: This status field represents indicator definition lifecycle, separate from 
progressApprovalStatus field which represents progress submission approval workflow.';


--
-- Name: indicatorlevel; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.indicatorlevel AS ENUM (
    'STRAT_TO_FUNC',
    'FUNC_TO_COLLEGE'
);


--
-- Name: milestone_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.milestone_status AS ENUM (
    'NOT_STARTED',
    'IN_PROGRESS',
    'COMPLETED',
    'DELAYED',
    'CANCELED'
);


--
-- Name: org_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.org_type AS ENUM (
    'admin',
    'functional',
    'academic'
);


--
-- Name: TYPE org_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TYPE public.org_type IS '组织类型枚举 - 三层架构: admin(系统管理层), functional(职能部门), academic(二级学院)';


--
-- Name: plan_level; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.plan_level AS ENUM (
    'STRAT_TO_FUNC',
    'FUNC_TO_COLLEGE'
);


--
-- Name: planlevel; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.planlevel AS ENUM (
    'PRIMARY',
    'SECONDARY'
);


--
-- Name: report_status; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.report_status AS ENUM (
    'DRAFT',
    'SUBMITTED',
    'RETURNED',
    'APPROVED',
    'REJECTED'
);


--
-- Name: task_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.task_type AS ENUM (
    'BASIC',
    'DEVELOPMENT',
    'REGULAR',
    'KEY',
    'SPECIAL',
    'QUANTITATIVE'
);


--
-- Name: tasktype; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.tasktype AS ENUM (
    'BASIC',
    'REGULAR',
    'KEY',
    'SPECIAL',
    'QUANTITATIVE',
    'DEVELOPMENT'
);


--
-- Name: update_approval_mode_to_parallel(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_approval_mode_to_parallel() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.approval_mode := 'SEQUENTIAL';
    RETURN NEW;
END;
$$;


--
-- Name: update_updated_at_column(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_updated_at_column() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

-- OpenTenBase 5.0 is based on PostgreSQL 10 and does not expose
-- default_table_access_method, so keep the baseline portable here.

--
-- Name: adhoc_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.adhoc_task (
    adhoc_task_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    due_at date,
    include_in_alert boolean NOT NULL,
    open_at date,
    require_indicator_report boolean NOT NULL,
    scope_type character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    task_desc text,
    task_title character varying(200) NOT NULL,
    creator_org_id bigint NOT NULL,
    cycle_id bigint NOT NULL,
    indicator_id bigint,
    CONSTRAINT adhoc_task_scope_type_check CHECK (((scope_type)::text = ANY (ARRAY[('ALL_ORGS'::character varying)::text, ('BY_DEPT_ISSUED_INDICATORS'::character varying)::text, ('CUSTOM'::character varying)::text]))),
    CONSTRAINT adhoc_task_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('OPEN'::character varying)::text, ('CLOSED'::character varying)::text, ('ARCHIVED'::character varying)::text])))
);


--
-- Name: adhoc_task_adhoc_task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.adhoc_task_adhoc_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: adhoc_task_adhoc_task_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.adhoc_task_adhoc_task_id_seq OWNED BY public.adhoc_task.adhoc_task_id;


--
-- Name: adhoc_task_indicator_map; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.adhoc_task_indicator_map (
    adhoc_task_id bigint NOT NULL,
    indicator_id bigint NOT NULL
);


--
-- Name: adhoc_task_target; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.adhoc_task_target (
    adhoc_task_id bigint NOT NULL,
    target_org_id bigint NOT NULL
);


--
-- Name: alert_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_event (
    event_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    actual_percent numeric(5,2) NOT NULL,
    detail_json jsonb,
    expected_percent numeric(5,2) NOT NULL,
    gap_percent numeric(5,2) NOT NULL,
    handled_note text,
    severity character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    handled_by bigint,
    indicator_id bigint NOT NULL,
    rule_id bigint NOT NULL,
    window_id bigint NOT NULL,
    CONSTRAINT alert_event_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('CRITICAL'::character varying)::text]))),
    CONSTRAINT alert_event_status_check CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('RESOLVED'::character varying)::text, ('CLOSED'::character varying)::text])))
);


--
-- Name: alert_event_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.alert_event_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: alert_event_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.alert_event_event_id_seq OWNED BY public.alert_event.event_id;


--
-- Name: alert_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_rule (
    rule_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    gap_threshold numeric(5,2) NOT NULL,
    is_enabled boolean NOT NULL,
    name character varying(100) NOT NULL,
    severity character varying(255) NOT NULL,
    cycle_id bigint NOT NULL,
    CONSTRAINT alert_rule_severity_check CHECK (((severity)::text = ANY (ARRAY[('INFO'::character varying)::text, ('WARNING'::character varying)::text, ('CRITICAL'::character varying)::text])))
);


--
-- Name: alert_rule_rule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.alert_rule_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: alert_rule_rule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.alert_rule_rule_id_seq OWNED BY public.alert_rule.rule_id;


--
-- Name: alert_window; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_window (
    window_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    cutoff_date date NOT NULL,
    is_default boolean NOT NULL,
    name character varying(100) NOT NULL,
    cycle_id bigint NOT NULL
);


--
-- Name: alert_window_window_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.alert_window_window_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: alert_window_window_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.alert_window_window_id_seq OWNED BY public.alert_window.window_id;


--
-- Name: attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attachment (
    id bigint NOT NULL,
    storage_driver character varying(16) DEFAULT 'FILE'::character varying NOT NULL,
    bucket character varying(128),
    object_key text NOT NULL,
    public_url text,
    original_name text NOT NULL,
    content_type character varying(128),
    file_ext character varying(16),
    size_bytes bigint NOT NULL,
    sha256 character(64),
    etag text,
    uploaded_by bigint NOT NULL,
    uploaded_at timestamp with time zone DEFAULT now() NOT NULL,
    remark text,
    is_deleted boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT attachment_size_bytes_check CHECK ((size_bytes >= 0))
)${otb_table_distribution};


--
-- Name: TABLE attachment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.attachment IS 'INSERT INTO attachment(storage_driver, bucket, object_key, original_name, content_type, file_ext, size_bytes, sha256, uploaded_by)
VALUES (''FILE'', NULL, ''2026/02/03/xxx.pdf'', ''验收材料.pdf'', ''application/pdf'', ''pdf'', 123456, ''...sha256...'', 1001)';


--
-- Name: COLUMN attachment.storage_driver; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.storage_driver IS 'FILE/S3/OSS/COS/MINIO';


--
-- Name: COLUMN attachment.bucket; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.bucket IS '对象存储桶；本地可为空';


--
-- Name: COLUMN attachment.object_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.object_key IS '核心：在存储层的唯一Key/相对路径';


--
-- Name: COLUMN attachment.public_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.public_url IS '可选：公开访问URL（如果是公有桶/静态服务器）';


--
-- Name: COLUMN attachment.original_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.original_name IS '文件名';


--
-- Name: COLUMN attachment.content_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.content_type IS '文件类型';


--
-- Name: COLUMN attachment.file_ext; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.file_ext IS '后缀';


--
-- Name: COLUMN attachment.sha256; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.sha256 IS '校验用';


--
-- Name: COLUMN attachment.etag; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.attachment.etag IS '验证用，暂时为空';


--
-- Name: attachment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.attachment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: attachment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.attachment_id_seq OWNED BY public.attachment.id;


--
-- Name: audit_flow_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_flow_def (
    id bigint NOT NULL,
    flow_code character varying(64) NOT NULL,
    flow_name character varying(128) NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    description character varying(255),
    version integer,
    entity_type character varying(64) DEFAULT 'PLAN_REPORT'::character varying NOT NULL
)${otb_table_distribution};


--
-- Name: TABLE audit_flow_def; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.audit_flow_def IS '审批流程定义表 - V25: 删除了 entity_type 和 biz_type 字段';


--
-- Name: COLUMN audit_flow_def.entity_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_flow_def.entity_type IS '实体类型: INDICATOR, PLAN_REPORT 等';


--
-- Name: audit_flow_def_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_flow_def_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_flow_def_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_flow_def_id_seq OWNED BY public.audit_flow_def.id;


--
-- Name: audit_instance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_instance (
    id bigint NOT NULL,
    status character varying(32) NOT NULL,
    started_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp(6) without time zone,
    entity_id bigint NOT NULL,
    entity_type character varying(255),
    flow_def_id bigint,
    is_deleted boolean NOT NULL,
    requester_id bigint,
    requester_org_id bigint,
    CONSTRAINT audit_instance_status_check CHECK (((status)::text = ANY (ARRAY[('IN_REVIEW'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: TABLE audit_instance; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.audit_instance IS 'Audit workflow instance. Approval context is resolved dynamically via ApprovalResolverService, not stored as snapshot columns.';


--
-- Name: COLUMN audit_instance.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_instance.status IS '审批实例状态：IN_REVIEW / APPROVED / REJECTED';


--
-- Name: COLUMN audit_instance.started_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_instance.started_at IS '启动时间';


--
-- Name: audit_instance_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_instance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_instance_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_instance_id_seq OWNED BY public.audit_instance.id;


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    log_id bigint NOT NULL,
    action character varying(255) NOT NULL,
    after_json jsonb,
    before_json jsonb,
    changed_fields jsonb,
    created_at timestamp(6) without time zone NOT NULL,
    entity_id bigint NOT NULL,
    entity_type character varying(255) NOT NULL,
    reason text,
    actor_org_id bigint,
    actor_user_id bigint,
    CONSTRAINT audit_log_action_check CHECK (((action)::text = ANY (ARRAY[('CREATE'::character varying)::text, ('UPDATE'::character varying)::text, ('DELETE'::character varying)::text, ('APPROVE'::character varying)::text, ('ARCHIVE'::character varying)::text, ('RESTORE'::character varying)::text]))),
    CONSTRAINT audit_log_entity_type_check CHECK (((entity_type)::text = ANY (ARRAY[('ORG'::character varying)::text, ('USER'::character varying)::text, ('CYCLE'::character varying)::text, ('TASK'::character varying)::text, ('INDICATOR'::character varying)::text, ('MILESTONE'::character varying)::text, ('REPORT'::character varying)::text, ('ADHOC_TASK'::character varying)::text, ('ALERT'::character varying)::text])))
);


--
-- Name: audit_log_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_log_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_log_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_log_log_id_seq OWNED BY public.audit_log.log_id;


--
-- Name: audit_step_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_step_def (
    id bigint NOT NULL,
    flow_id bigint NOT NULL,
    step_name character varying(128) NOT NULL,
    step_type character varying(255),
    role_id bigint,
    is_terminal boolean DEFAULT false,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    step_no integer
);


--
-- Name: COLUMN audit_step_def.step_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_step_def.step_name IS '审核节点名称';


--
-- Name: audit_step_def_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_step_def_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_step_def_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_step_def_id_seq OWNED BY public.audit_step_def.id;


--
-- Name: audit_step_instance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_step_instance (
    id bigint NOT NULL,
    instance_id bigint,
    step_name character varying(128) NOT NULL,
    approved_at timestamp(6) without time zone,
    approver_id bigint,
    comment character varying(255),
    status character varying(255),
    step_def_id bigint,
    approver_org_id bigint,
    step_no integer,
    created_at timestamp without time zone
);


--
-- Name: TABLE audit_step_instance; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.audit_step_instance IS '流程节点实例表：记录某个流程实例在某个节点上的实际处理情况';


--
-- Name: COLUMN audit_step_instance.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_step_instance.id IS '主键';


--
-- Name: COLUMN audit_step_instance.instance_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_step_instance.instance_id IS '所属流程实例ID，关联 audit_instance.id';


--
-- Name: COLUMN audit_step_instance.step_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_step_instance.step_name IS '节点名称（冗余保存，便于展示）';


--
-- Name: COLUMN audit_step_instance.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_step_instance.status IS '审批节点状态：PENDING / WAITING / APPROVED / REJECTED / WITHDRAWN';


--
-- Name: audit_step_instance_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_step_instance_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_step_instance_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_step_instance_id_seq OWNED BY public.audit_step_instance.id;


--
-- Name: cycle; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cycle (
    id bigint NOT NULL,
    cycle_name character varying(100) NOT NULL,
    year integer NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    description text,
    created_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE cycle; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.cycle IS '考核周期表';


--
-- Name: cycle_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cycle_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cycle_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.cycle_id_seq OWNED BY public.cycle.id;


--
-- Name: idempotency_records; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.idempotency_records (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL,
    http_method character varying(10),
    idempotency_key character varying(64) NOT NULL,
    request_path character varying(255),
    response_body text,
    status character varying(20),
    status_code integer,
    CONSTRAINT idempotency_records_status_check CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text])))
)${otb_table_distribution};


--
-- Name: idempotency_records_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.idempotency_records_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: idempotency_records_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.idempotency_records_id_seq OWNED BY public.idempotency_records.id;


--
-- Name: indicator; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.indicator (
    id bigint NOT NULL,
    task_id bigint,
    parent_indicator_id bigint,
    indicator_desc text NOT NULL,
    weight_percent numeric(38,2) DEFAULT 100 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    remark text,
    created_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    type character varying(20) DEFAULT '定量'::character varying NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    owner_org_id bigint NOT NULL,
    target_org_id bigint NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    responsible_user_id bigint,
    is_enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT indicator_desc_not_blank_check CHECK ((btrim(indicator_desc) <> ''::text)),
    CONSTRAINT indicator_progress_check CHECK (((progress >= 0) AND (progress <= 100))),
    CONSTRAINT indicator_sort_order_non_negative_check CHECK ((sort_order >= 0)),
    CONSTRAINT indicator_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PENDING'::character varying)::text, ('DISTRIBUTED'::character varying)::text]))),
    CONSTRAINT indicator_type_check CHECK (((type)::text = ANY (ARRAY[('定量'::character varying)::text, ('定性'::character varying)::text]))),
    CONSTRAINT indicator_weight_percent_positive_check CHECK ((weight_percent > (0)::numeric))
);


--
-- Name: TABLE indicator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.indicator IS '指标表 - 支持自引用分层';


--
-- Name: COLUMN indicator.task_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.task_id IS '为空的话，即是level2的指标';


--
-- Name: COLUMN indicator.parent_indicator_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.parent_indicator_id IS '只有level2有这个数据';


--
-- Name: COLUMN indicator.indicator_desc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.indicator_desc IS '指标描述，具体的指标值也在描述里';


--
-- Name: COLUMN indicator.weight_percent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.weight_percent IS '权重';


--
-- Name: COLUMN indicator.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.type IS '指标类型: 定量/定性';


--
-- Name: COLUMN indicator.progress; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.progress IS '最新进度';


--
-- Name: COLUMN indicator.owner_org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.owner_org_id IS 'Owner organization (functional department)';


--
-- Name: COLUMN indicator.target_org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.target_org_id IS 'Target organization (functional department or college)';


--
-- Name: COLUMN indicator.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.indicator.status IS '指标状态: DRAFT=草稿, PENDING=待审批, DISTRIBUTED=已下发（其他状态在审批流程中管理）';


--
-- Name: indicator_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.indicator_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: indicator_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.indicator_id_seq OWNED BY public.indicator.id;


--
-- Name: indicator_milestone; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.indicator_milestone (
    id bigint NOT NULL,
    indicator_id bigint NOT NULL,
    milestone_name character varying(200) NOT NULL,
    milestone_desc text,
    due_date date NOT NULL,
    status character varying DEFAULT 'NOT_STARTED'::public.milestone_status NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    target_progress integer DEFAULT 0,
    is_paired boolean DEFAULT false
);


--
-- Name: TABLE indicator_milestone; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.indicator_milestone IS '指标里程碑关联表 - V26: 删除了 inherited_from 字段，通过 indicator_id 关联实现相同功能';


--
-- Name: indicator_milestone_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.indicator_milestone_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: indicator_milestone_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.indicator_milestone_id_seq OWNED BY public.indicator_milestone.id;


--
-- Name: sys_org; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_org (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    type public.org_type NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(6) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    parent_org_id bigint,
    level integer,
    is_deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_sys_org_type CHECK ((type = ANY (ARRAY['admin'::public.org_type, 'functional'::public.org_type, 'academic'::public.org_type])))
)${otb_table_distribution};


--
-- Name: TABLE sys_org; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_org IS '组织表 - 战略发展部/职能部门/二级学院/系部';


--
-- Name: COLUMN sys_org.type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_org.type IS '组织类型: admin(系统管理层), functional(职能部门), academic(二级学院)';


--
-- Name: COLUMN sys_org.parent_org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_org.parent_org_id IS '父组织ID，用于构建组织层级关系';


--
-- Name: COLUMN sys_org.level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_org.level IS '组织层级，1=顶级组织，2=二级组织，以此类推';


--
-- Name: org_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.org_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: org_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.org_id_seq OWNED BY public.sys_org.id;


--
-- Name: plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan (
    id bigint NOT NULL,
    cycle_id bigint NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    target_org_id bigint NOT NULL,
    created_by_org_id bigint NOT NULL,
    plan_level public.plan_level NOT NULL,
    status character varying DEFAULT 'DRAFT'::character varying NOT NULL,
    audit_instance_id bigint,
    CONSTRAINT ck_plan_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PENDING'::character varying)::text, ('DISTRIBUTED'::character varying)::text, ('IN_REVIEW'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text, ('RETURNED'::character varying)::text, ('WITHDRAWN'::character varying)::text])))
);


--
-- Name: TABLE plan; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.plan IS '用于统一管理一批同时下发的指标。
战略发展部给职能部门发布的任务集合对应一个plan。
职能部门给二级学院发布的指标集合对应一个plan。';


--
-- Name: COLUMN plan.cycle_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan.cycle_id IS '周期';


--
-- Name: COLUMN plan.target_org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan.target_org_id IS '接受计划组织';


--
-- Name: COLUMN plan.created_by_org_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan.created_by_org_id IS '创建组织';


--
-- Name: COLUMN plan.plan_level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan.plan_level IS '指标层级: STRAT_TO_FUNC-战略到职能, FUNC_TO_COLLEGE-职能到学院';


--
-- Name: COLUMN plan.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan.status IS '与下发流程状态一致';


--
-- Name: COLUMN plan.audit_instance_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan.audit_instance_id IS 'Optional runtime link to audit_instance.id. NULL means the plan has not started approval yet.';


--
-- Name: plan_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.plan_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: plan_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.plan_id_seq OWNED BY public.plan.id;


--
-- Name: plan_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_report (
    id bigint NOT NULL,
    plan_id bigint NOT NULL,
    report_month character varying(255) NOT NULL,
    report_org_type character varying(16) NOT NULL,
    report_org_id bigint NOT NULL,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    submitted_at timestamp with time zone,
    remark text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    created_by bigint,
    audit_instance_id bigint,
    CONSTRAINT plan_report_report_month_check CHECK (((report_month)::text ~ '^[0-9]{6}$'::text)),
    CONSTRAINT plan_report_report_org_type_check CHECK (((report_org_type)::text = ANY (ARRAY[('FUNC_DEPT'::character varying)::text, ('COLLEGE'::character varying)::text]))),
    CONSTRAINT plan_report_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('IN_REVIEW'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
)${otb_table_distribution};


--
-- Name: TABLE plan_report; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.plan_report IS 'plan提交记录。提交时如果目标月份已有记录，则在其基础上修改，否则新增目标月份的记录。';


--
-- Name: COLUMN plan_report.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan_report.status IS '应该是流程中的最新状态';


--
-- Name: COLUMN plan_report.audit_instance_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan_report.audit_instance_id IS 'Optional runtime link to audit_instance.id. NULL means the monthly report has not started approval yet.';


--
-- Name: plan_report_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.plan_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: plan_report_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.plan_report_id_seq OWNED BY public.plan_report.id;


--
-- Name: plan_report_indicator; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_report_indicator (
    id bigint NOT NULL,
    report_id bigint NOT NULL,
    indicator_id bigint NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    milestone_note text,
    comment text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT plan_report_indicator_progress_check CHECK (((progress >= 0) AND (progress <= 100)))
)${otb_table_distribution};


--
-- Name: TABLE plan_report_indicator; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.plan_report_indicator IS '填报记录表（包括历史）';


--
-- Name: COLUMN plan_report_indicator.milestone_note; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.plan_report_indicator.milestone_note IS '用于记录对应里程碑的情况，可以为空';


--
-- Name: plan_report_indicator_attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan_report_indicator_attachment (
    id bigint NOT NULL,
    plan_report_indicator_id bigint NOT NULL,
    attachment_id bigint NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
)${otb_table_distribution};


--
-- Name: plan_report_indicator_attachment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.plan_report_indicator_attachment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: plan_report_indicator_attachment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.plan_report_indicator_attachment_id_seq OWNED BY public.plan_report_indicator_attachment.id;


--
-- Name: plan_report_indicator_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.plan_report_indicator_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: plan_report_indicator_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.plan_report_indicator_id_seq OWNED BY public.plan_report_indicator.id;


--
-- Name: progress_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.progress_report (
    report_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    achieved_milestone boolean NOT NULL,
    is_final boolean NOT NULL,
    narrative text,
    percent_complete numeric(5,2) NOT NULL,
    reported_at timestamp(6) without time zone,
    status character varying(255) NOT NULL,
    version_no integer NOT NULL,
    adhoc_task_id bigint,
    indicator_id bigint NOT NULL,
    milestone_id bigint,
    reporter_id bigint NOT NULL,
    CONSTRAINT progress_report_status_check CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('SUBMITTED'::character varying)::text, ('RETURNED'::character varying)::text, ('APPROVED'::character varying)::text, ('REJECTED'::character varying)::text])))
);


--
-- Name: progress_report_report_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.progress_report_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: progress_report_report_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.progress_report_report_id_seq OWNED BY public.progress_report.report_id;


--
-- Name: refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_tokens (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    device_info character varying(255),
    expires_at timestamp(6) without time zone NOT NULL,
    ip_address character varying(45),
    revoked_at timestamp(6) without time zone,
    token_hash character varying(64) NOT NULL,
    user_id bigint NOT NULL
)${otb_table_distribution};


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: refresh_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.refresh_tokens_id_seq OWNED BY public.refresh_tokens.id;


--
-- Name: sys_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_task (
    task_id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    remark text,
    sort_order integer NOT NULL,
    task_type character varying(255) NOT NULL,
    created_by_org_id bigint NOT NULL,
    cycle_id bigint NOT NULL,
    org_id bigint NOT NULL,
    is_deleted boolean NOT NULL,
    plan_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    "desc" character varying(255)
);


--
-- Name: TABLE sys_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_task IS 'Strategic task table (migrated from task on 2026-02-10)';


--
-- Name: COLUMN sys_task.cycle_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_task.cycle_id IS '考核周期';


--
-- Name: COLUMN sys_task.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_task.name IS '冗余？';


--
-- Name: strategic_task_task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.strategic_task_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: strategic_task_task_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.strategic_task_task_id_seq OWNED BY public.sys_task.task_id;


--
-- Name: sys_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_permission (
    id bigint NOT NULL,
    perm_code character varying(128) NOT NULL,
    perm_name character varying(128) NOT NULL,
    perm_type character varying(16) NOT NULL,
    parent_id bigint,
    route_path character varying(256),
    page_key character varying(128),
    action_key character varying(128),
    sort_order integer DEFAULT 0 NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    remark text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sys_permission_perm_type_check CHECK (((perm_type)::text = ANY (ARRAY[('PAGE'::character varying)::text, ('BUTTON'::character varying)::text])))
)${otb_table_distribution};


--
-- Name: TABLE sys_permission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_permission IS '权限资源表：统一定义页面（PAGE）与按钮（BUTTON），按钮通过parent_id归属到页面';


--
-- Name: COLUMN sys_permission.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.id IS '主键';


--
-- Name: COLUMN sys_permission.perm_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.perm_code IS '权限编码（唯一，建议前端/后端统一使用）';


--
-- Name: COLUMN sys_permission.perm_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.perm_name IS '权限名称（中文展示）';


--
-- Name: COLUMN sys_permission.perm_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.perm_type IS '权限类型：PAGE=页面，BUTTON=按钮';


--
-- Name: COLUMN sys_permission.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.parent_id IS '父权限ID：按钮指向其所属页面；页面为空';


--
-- Name: COLUMN sys_permission.route_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.route_path IS '页面路由路径（仅PAGE使用）';


--
-- Name: COLUMN sys_permission.page_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.page_key IS '页面标识（可用于前端定位、埋点、菜单渲染）';


--
-- Name: COLUMN sys_permission.action_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.action_key IS '按钮动作标识（仅BUTTON使用，如 submit/approve/export）';


--
-- Name: COLUMN sys_permission.sort_order; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.sort_order IS '排序号';


--
-- Name: COLUMN sys_permission.is_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.is_enabled IS '是否启用';


--
-- Name: COLUMN sys_permission.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.remark IS '备注说明';


--
-- Name: COLUMN sys_permission.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.created_at IS '创建时间';


--
-- Name: COLUMN sys_permission.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_permission.updated_at IS '更新时间';


--
-- Name: sys_permission_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_permission_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sys_permission_id_seq OWNED BY public.sys_permission.id;


--
-- Name: sys_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_role (
    id bigint NOT NULL,
    role_code character varying(64) NOT NULL,
    role_name character varying(128) NOT NULL,
    data_access_mode character varying(16) DEFAULT 'OWN_ORG'::character varying NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    remark text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sys_role_data_access_mode_check CHECK (((data_access_mode)::text = ANY (ARRAY[('ALL'::character varying)::text, ('OWN_ORG'::character varying)::text])))
)${otb_table_distribution};


--
-- Name: TABLE sys_role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_role IS '角色表：RBAC角色定义（决定页面/按钮权限）；data_access_mode用于区分战略部全量访问与普通组织仅本组织';


--
-- Name: COLUMN sys_role.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.id IS '主键';


--
-- Name: COLUMN sys_role.role_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.role_code IS '角色编码（唯一）';


--
-- Name: COLUMN sys_role.role_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.role_name IS '角色名称';


--
-- Name: COLUMN sys_role.data_access_mode; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.data_access_mode IS '数据访问模式：ALL=全量可见（如战略发展部），OWN_ORG=仅本组织数据（一期可不启用）';


--
-- Name: COLUMN sys_role.is_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.is_enabled IS '是否启用';


--
-- Name: COLUMN sys_role.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.remark IS '备注说明';


--
-- Name: COLUMN sys_role.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.created_at IS '创建时间';


--
-- Name: COLUMN sys_role.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role.updated_at IS '更新时间';


--
-- Name: sys_role_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_role_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sys_role_id_seq OWNED BY public.sys_role.id;


--
-- Name: sys_role_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_role_permission (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    perm_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
)${otb_table_distribution};


--
-- Name: TABLE sys_role_permission; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_role_permission IS '角色权限关联表：给角色授予页面/按钮权限';


--
-- Name: COLUMN sys_role_permission.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role_permission.id IS '主键';


--
-- Name: COLUMN sys_role_permission.role_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role_permission.role_id IS '角色ID';


--
-- Name: COLUMN sys_role_permission.perm_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role_permission.perm_id IS '权限资源ID（页面或按钮）';


--
-- Name: COLUMN sys_role_permission.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_role_permission.created_at IS '创建时间';


--
-- Name: sys_role_permission_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_role_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_role_permission_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sys_role_permission_id_seq OWNED BY public.sys_role_permission.id;


--
-- Name: sys_task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    is_active boolean NOT NULL,
    password_hash character varying(255) NOT NULL,
    real_name character varying(50) NOT NULL,
    sso_id character varying(100),
    username character varying(50) NOT NULL,
    org_id bigint NOT NULL
)${otb_table_distribution};


--
-- Name: TABLE sys_user; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_user IS 'System user table (renamed from app_user on 2026-02-10)';


--
-- Name: sys_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user_role (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
)${otb_table_distribution};


--
-- Name: TABLE sys_user_role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.sys_user_role IS '用户角色关联表：用户可拥有多个角色，角色也可分配给多个用户';


--
-- Name: COLUMN sys_user_role.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_user_role.id IS '主键';


--
-- Name: COLUMN sys_user_role.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_user_role.user_id IS '用户ID';


--
-- Name: COLUMN sys_user_role.role_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_user_role.role_id IS '角色ID';


--
-- Name: COLUMN sys_user_role.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sys_user_role.created_at IS '创建时间';


--
-- Name: sys_user_role_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_user_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_user_role_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sys_user_role_id_seq OWNED BY public.sys_user_role.id;


--
-- Name: sys_user_user_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_user_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_user_user_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sys_user_user_id_seq OWNED BY public.sys_user.id;


--
-- Name: task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_plan_assessment_cycle_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.task_plan_assessment_cycle_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: task_plan_assessment_cycle_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.task_plan_assessment_cycle_id_seq OWNED BY public.plan.cycle_id;


--
-- Name: warn_level; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.warn_level (
    id bigint NOT NULL,
    level_code character varying(32) NOT NULL,
    level_name character varying(64) NOT NULL,
    severity integer NOT NULL,
    remark text,
    CONSTRAINT warn_level_severity_check CHECK ((severity >= 0))
)${otb_table_distribution};


--
-- Name: TABLE warn_level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.warn_level IS '预警等级字典表：定义系统统一使用的预警等级（如正常/预警/严重/危急），用于实时预警与历史预警事件';


--
-- Name: COLUMN warn_level.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.warn_level.id IS '主键';


--
-- Name: COLUMN warn_level.level_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.warn_level.level_code IS '预警等级编码（OK / INFO / WARN / MAJOR / CRITICAL）';


--
-- Name: COLUMN warn_level.level_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.warn_level.level_name IS '预警等级名称（中文展示用）';


--
-- Name: COLUMN warn_level.severity; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.warn_level.severity IS '严重程度数值，数值越大表示越严重，用于排序和统计';


--
-- Name: COLUMN warn_level.remark; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.warn_level.remark IS '备注说明';


--
-- Name: warn_level_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.warn_level_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: warn_level_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.warn_level_id_seq OWNED BY public.warn_level.id;


--
-- Name: workflow_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workflow_task (
    task_id bigint NOT NULL,
    assignee_id bigint,
    assignee_org_id bigint,
    completed_at timestamp(6) without time zone,
    current_step character varying(255),
    due_date timestamp(6) without time zone,
    error_message text,
    initiator_id bigint,
    initiator_org_id bigint,
    next_step character varying(255),
    result text,
    started_at timestamp(6) without time zone,
    status character varying(255) NOT NULL,
    task_name character varying(255) NOT NULL,
    task_type character varying(255),
    workflow_id character varying(255) NOT NULL,
    workflow_type character varying(255) NOT NULL,
    id bigint
)${otb_table_distribution};


--
-- Name: workflow_task_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workflow_task_history (
    task_id bigint NOT NULL,
    history character varying(255)
);


--
-- Name: workflow_task_task_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.workflow_task_task_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: workflow_task_task_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.workflow_task_task_id_seq OWNED BY public.workflow_task.task_id;


--
-- Name: adhoc_task adhoc_task_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task ALTER COLUMN adhoc_task_id SET DEFAULT nextval('public.adhoc_task_adhoc_task_id_seq'::regclass);


--
-- Name: alert_event event_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event ALTER COLUMN event_id SET DEFAULT nextval('public.alert_event_event_id_seq'::regclass);


--
-- Name: alert_rule rule_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule ALTER COLUMN rule_id SET DEFAULT nextval('public.alert_rule_rule_id_seq'::regclass);


--
-- Name: alert_window window_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_window ALTER COLUMN window_id SET DEFAULT nextval('public.alert_window_window_id_seq'::regclass);


--
-- Name: attachment id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attachment ALTER COLUMN id SET DEFAULT nextval('public.attachment_id_seq'::regclass);


--
-- Name: audit_flow_def id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_flow_def ALTER COLUMN id SET DEFAULT nextval('public.audit_flow_def_id_seq'::regclass);


--
-- Name: audit_instance id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_instance ALTER COLUMN id SET DEFAULT nextval('public.audit_instance_id_seq'::regclass);


--
-- Name: audit_log log_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log ALTER COLUMN log_id SET DEFAULT nextval('public.audit_log_log_id_seq'::regclass);


--
-- Name: audit_step_def id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_step_def ALTER COLUMN id SET DEFAULT nextval('public.audit_step_def_id_seq'::regclass);


--
-- Name: audit_step_instance id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_step_instance ALTER COLUMN id SET DEFAULT nextval('public.audit_step_instance_id_seq'::regclass);


--
-- Name: cycle id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cycle ALTER COLUMN id SET DEFAULT nextval('public.cycle_id_seq'::regclass);


--
-- Name: idempotency_records id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records ALTER COLUMN id SET DEFAULT nextval('public.idempotency_records_id_seq'::regclass);


--
-- Name: indicator id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.indicator ALTER COLUMN id SET DEFAULT nextval('public.indicator_id_seq'::regclass);


--
-- Name: indicator_milestone id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.indicator_milestone ALTER COLUMN id SET DEFAULT nextval('public.indicator_milestone_id_seq'::regclass);


--
-- Name: plan id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan ALTER COLUMN id SET DEFAULT nextval('public.plan_id_seq'::regclass);


--
-- Name: plan_report id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report ALTER COLUMN id SET DEFAULT nextval('public.plan_report_id_seq'::regclass);


--
-- Name: plan_report_indicator id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report_indicator ALTER COLUMN id SET DEFAULT nextval('public.plan_report_indicator_id_seq'::regclass);


--
-- Name: plan_report_indicator_attachment id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report_indicator_attachment ALTER COLUMN id SET DEFAULT nextval('public.plan_report_indicator_attachment_id_seq'::regclass);


--
-- Name: progress_report report_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.progress_report ALTER COLUMN report_id SET DEFAULT nextval('public.progress_report_report_id_seq'::regclass);


--
-- Name: refresh_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens ALTER COLUMN id SET DEFAULT nextval('public.refresh_tokens_id_seq'::regclass);


--
-- Name: sys_org id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_org ALTER COLUMN id SET DEFAULT nextval('public.org_id_seq'::regclass);


--
-- Name: sys_permission id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_permission ALTER COLUMN id SET DEFAULT nextval('public.sys_permission_id_seq'::regclass);


--
-- Name: sys_role id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role ALTER COLUMN id SET DEFAULT nextval('public.sys_role_id_seq'::regclass);


--
-- Name: sys_role_permission id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_permission ALTER COLUMN id SET DEFAULT nextval('public.sys_role_permission_id_seq'::regclass);


--
-- Name: sys_task task_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_task ALTER COLUMN task_id SET DEFAULT nextval('public.strategic_task_task_id_seq'::regclass);


--
-- Name: sys_user id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user ALTER COLUMN id SET DEFAULT nextval('public.sys_user_user_id_seq'::regclass);


--
-- Name: sys_user_role id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_role ALTER COLUMN id SET DEFAULT nextval('public.sys_user_role_id_seq'::regclass);


--
-- Name: warn_level id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warn_level ALTER COLUMN id SET DEFAULT nextval('public.warn_level_id_seq'::regclass);


--
-- Name: workflow_task task_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_task ALTER COLUMN task_id SET DEFAULT nextval('public.workflow_task_task_id_seq'::regclass);


--
-- Name: adhoc_task_indicator_map adhoc_task_indicator_map_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task_indicator_map
    ADD CONSTRAINT adhoc_task_indicator_map_pkey PRIMARY KEY (adhoc_task_id, indicator_id);


--
-- Name: adhoc_task adhoc_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task
    ADD CONSTRAINT adhoc_task_pkey PRIMARY KEY (adhoc_task_id);


--
-- Name: adhoc_task_target adhoc_task_target_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task_target
    ADD CONSTRAINT adhoc_task_target_pkey PRIMARY KEY (adhoc_task_id, target_org_id);


--
-- Name: alert_event alert_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT alert_event_pkey PRIMARY KEY (event_id);


--
-- Name: alert_rule alert_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_pkey PRIMARY KEY (rule_id);


--
-- Name: alert_window alert_window_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_window
    ADD CONSTRAINT alert_window_pkey PRIMARY KEY (window_id);


--
-- Name: cycle assessment_cycle_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cycle
    ADD CONSTRAINT assessment_cycle_pkey PRIMARY KEY (id);


--
-- Name: attachment attachment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attachment
    ADD CONSTRAINT attachment_pkey PRIMARY KEY (id);


--
-- Name: audit_flow_def audit_flow_def_flow_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_flow_def
    ADD CONSTRAINT audit_flow_def_flow_code_key UNIQUE (flow_code);


--
-- Name: audit_flow_def audit_flow_def_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_flow_def
    ADD CONSTRAINT audit_flow_def_pkey PRIMARY KEY (id);


--
-- Name: audit_instance audit_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_instance
    ADD CONSTRAINT audit_instance_pkey PRIMARY KEY (id);


--
-- Name: audit_log audit_log_pkey1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey1 PRIMARY KEY (log_id);


--
-- Name: audit_step_def audit_step_def_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_step_def
    ADD CONSTRAINT audit_step_def_pkey PRIMARY KEY (id);


--
-- Name: audit_step_instance audit_step_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_step_instance
    ADD CONSTRAINT audit_step_instance_pkey PRIMARY KEY (id);


--
-- Name: idempotency_records idempotency_records_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);


--
-- Name: indicator indicator_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.indicator
    ADD CONSTRAINT indicator_pkey PRIMARY KEY (id);


--
-- Name: indicator_milestone milestone_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.indicator_milestone
    ADD CONSTRAINT milestone_pkey PRIMARY KEY (id);


--
-- Name: sys_org org_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_org
    ADD CONSTRAINT org_pkey PRIMARY KEY (id);


--
-- Name: plan_report_indicator_attachment plan_report_indicator_attachm_plan_report_indicator_id_atta_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report_indicator_attachment
    ADD CONSTRAINT plan_report_indicator_attachm_plan_report_indicator_id_atta_key UNIQUE (plan_report_indicator_id, attachment_id);


--
-- Name: plan_report_indicator_attachment plan_report_indicator_attachment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report_indicator_attachment
    ADD CONSTRAINT plan_report_indicator_attachment_pkey PRIMARY KEY (id);


--
-- Name: plan_report_indicator plan_report_indicator_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report_indicator
    ADD CONSTRAINT plan_report_indicator_pkey PRIMARY KEY (id);


--
-- Name: plan_report plan_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report
    ADD CONSTRAINT plan_report_pkey PRIMARY KEY (id);


--
-- Name: progress_report progress_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.progress_report
    ADD CONSTRAINT progress_report_pkey PRIMARY KEY (report_id);


--
-- Name: refresh_tokens refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: sys_task strategic_task_pkey1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_task
    ADD CONSTRAINT strategic_task_pkey1 PRIMARY KEY (task_id);


--
-- Name: sys_permission sys_permission_perm_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_permission
    ADD CONSTRAINT sys_permission_perm_code_key UNIQUE (perm_code);


--
-- Name: sys_permission sys_permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_permission
    ADD CONSTRAINT sys_permission_pkey PRIMARY KEY (id);


--
-- Name: sys_role_permission sys_role_permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_permission
    ADD CONSTRAINT sys_role_permission_pkey PRIMARY KEY (id);


--
-- Name: sys_role sys_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role
    ADD CONSTRAINT sys_role_pkey PRIMARY KEY (id);


--
-- Name: sys_role sys_role_role_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role
    ADD CONSTRAINT sys_role_role_code_key UNIQUE (role_code);


--
-- Name: sys_user sys_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user
    ADD CONSTRAINT sys_user_pkey PRIMARY KEY (id);


--
-- Name: sys_user_role sys_user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_role
    ADD CONSTRAINT sys_user_role_pkey PRIMARY KEY (id);


--
-- Name: sys_user sys_user_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user
    ADD CONSTRAINT sys_user_username_key UNIQUE (username);


--
-- Name: plan task_plan_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan
    ADD CONSTRAINT task_plan_pk PRIMARY KEY (id);


--
-- Name: refresh_tokens uk_o2mlirhldriil2y7krapq4frt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT uk_o2mlirhldriil2y7krapq4frt UNIQUE (token_hash);


--
-- Name: idempotency_records uk_ol0gjg0uap11mq1y9ug506f1i; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.idempotency_records
    ADD CONSTRAINT uk_ol0gjg0uap11mq1y9ug506f1i UNIQUE (idempotency_key);


--
-- Name: sys_org uk_sys_org_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_org
    ADD CONSTRAINT uk_sys_org_name UNIQUE (name);


--
-- Name: plan_report uq_plan_report; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report
    ADD CONSTRAINT uq_plan_report UNIQUE (plan_id, report_month, report_org_type, report_org_id);


--
-- Name: plan_report_indicator uq_report_indicator; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan_report_indicator
    ADD CONSTRAINT uq_report_indicator UNIQUE (report_id, indicator_id);


--
-- Name: sys_role_permission uq_sys_role_perm; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_permission
    ADD CONSTRAINT uq_sys_role_perm UNIQUE (role_id, perm_id);


--
-- Name: sys_user_role uq_sys_user_role; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_role
    ADD CONSTRAINT uq_sys_user_role UNIQUE (user_id, role_id);


--
-- Name: warn_level warn_level_level_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warn_level
    ADD CONSTRAINT warn_level_level_code_key UNIQUE (level_code);


--
-- Name: warn_level warn_level_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.warn_level
    ADD CONSTRAINT warn_level_pkey PRIMARY KEY (id);


--
-- Name: workflow_task workflow_task_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_task
    ADD CONSTRAINT workflow_task_id_key UNIQUE (id);


--
-- Name: workflow_task workflow_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workflow_task
    ADD CONSTRAINT workflow_task_pkey PRIMARY KEY (task_id);


--
-- Name: idx_audit_instance_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_instance_status ON public.audit_instance USING btree (status);


--
-- Name: idx_audit_step_def_flow_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_step_def_flow_id ON public.audit_step_def USING btree (flow_id);


--
-- Name: idx_cycle_year; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_cycle_year ON public.cycle USING btree (year);


--
-- Name: idx_idempotency_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_expires_at ON public.idempotency_records USING btree (expires_at);


--
-- Name: idx_idempotency_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_idempotency_key ON public.idempotency_records USING btree (idempotency_key);


--
-- Name: idx_indicator_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_created_at ON public.indicator USING btree (created_at);


--
-- Name: idx_indicator_deleted_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_deleted_status ON public.indicator USING btree (is_deleted, status);


--
-- Name: idx_indicator_owner_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_owner_org ON public.indicator USING btree (owner_org_id);


--
-- Name: idx_indicator_owner_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_owner_status ON public.indicator USING btree (owner_org_id, status);


--
-- Name: idx_indicator_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_parent ON public.indicator USING btree (parent_indicator_id);


--
-- Name: idx_indicator_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_status ON public.indicator USING btree (status);


--
-- Name: idx_indicator_status_deleted; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_status_deleted ON public.indicator USING btree (status, is_deleted);


--
-- Name: idx_indicator_target_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_target_org ON public.indicator USING btree (target_org_id);


--
-- Name: idx_indicator_target_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_target_status ON public.indicator USING btree (target_org_id, status);


--
-- Name: idx_indicator_task; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_task ON public.indicator USING btree (task_id);


--
-- Name: idx_indicator_task_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_task_id ON public.indicator USING btree (task_id);


--
-- Name: INDEX idx_indicator_task_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.idx_indicator_task_id IS '优化按任务查询指标的性能';


--
-- Name: idx_indicator_type1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_indicator_type1 ON public.indicator USING btree (type);


--
-- Name: idx_milestone_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_milestone_due ON public.indicator_milestone USING btree (due_date);


--
-- Name: idx_milestone_indicator; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_milestone_indicator ON public.indicator_milestone USING btree (indicator_id);


--
-- Name: idx_milestone_indicator_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_milestone_indicator_id ON public.indicator_milestone USING btree (indicator_id);


--
-- Name: INDEX idx_milestone_indicator_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON INDEX public.idx_milestone_indicator_id IS '优化批量加载里程碑的性能';


--
-- Name: idx_milestone_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_milestone_status ON public.indicator_milestone USING btree (status);


--
-- Name: idx_org_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_org_type ON public.sys_org USING btree (type);


--
-- Name: idx_plan_cycle_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_cycle_id ON public.plan USING btree (cycle_id);


--
-- Name: idx_plan_report_plan_month; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_report_plan_month ON public.plan_report USING btree (plan_id, report_month);


--
-- Name: idx_plan_report_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_report_status ON public.plan_report USING btree (status);


--
-- Name: idx_refresh_tokens_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_expires_at ON public.refresh_tokens USING btree (expires_at);


--
-- Name: idx_refresh_tokens_token_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_token_hash ON public.refresh_tokens USING btree (token_hash);


--
-- Name: idx_refresh_tokens_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_tokens_user_id ON public.refresh_tokens USING btree (user_id);


--
-- Name: idx_report_indicator_indicator; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_indicator_indicator ON public.plan_report_indicator USING btree (indicator_id);


--
-- Name: idx_report_indicator_report; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_indicator_report ON public.plan_report_indicator USING btree (report_id);


--
-- Name: idx_sys_org_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_org_active ON public.sys_org USING btree (is_active);


--
-- Name: idx_sys_org_level; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_org_level ON public.sys_org USING btree (level);


--
-- Name: idx_sys_org_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_org_parent ON public.sys_org USING btree (parent_org_id);


--
-- Name: idx_sys_org_sort; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_org_sort ON public.sys_org USING btree (sort_order);


--
-- Name: idx_sys_org_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_org_type ON public.sys_org USING btree (type);


--
-- Name: idx_sys_perm_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_perm_parent ON public.sys_permission USING btree (parent_id);


--
-- Name: idx_sys_perm_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_perm_type ON public.sys_permission USING btree (perm_type);


--
-- Name: idx_sys_role_perm_perm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_role_perm_perm ON public.sys_role_permission USING btree (perm_id);


--
-- Name: idx_sys_role_perm_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_role_perm_role ON public.sys_role_permission USING btree (role_id);


--
-- Name: idx_sys_user_role_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_user_role_role ON public.sys_user_role USING btree (role_id);


--
-- Name: idx_sys_user_role_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_user_role_user ON public.sys_user_role USING btree (user_id);


--
-- Name: idx_task_plan_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_task_plan_id ON public.sys_task USING btree (plan_id);


--
-- Name: ix_attachment_sha256; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_attachment_sha256 ON public.attachment USING btree (sha256);


--
-- Name: ix_attachment_uploaded_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_attachment_uploaded_at ON public.attachment USING btree (uploaded_at);


--
-- Name: ix_pri_attach_attachment_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_pri_attach_attachment_id ON public.plan_report_indicator_attachment USING btree (attachment_id);


--
-- Name: ix_pri_attach_pri_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_pri_attach_pri_id ON public.plan_report_indicator_attachment USING btree (plan_report_indicator_id);


--
-- Name: ux_attachment_object; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_attachment_object ON public.attachment USING btree (storage_driver, bucket, object_key);


--
-- Name: cycle trg_cycle_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_cycle_updated_at BEFORE UPDATE ON public.cycle FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: indicator trg_indicator_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_indicator_updated_at BEFORE UPDATE ON public.indicator FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: indicator_milestone trg_milestone_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_milestone_updated_at BEFORE UPDATE ON public.indicator_milestone FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();


--
-- Name: sys_org trg_org_updated_at; Type: TRIGGER; Schema: public; Owner: -
--

${sys_org_updated_at_trigger}


--
-- Name: alert_event fk1sehn6mpxtshf781kyly6ecre; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT fk1sehn6mpxtshf781kyly6ecre FOREIGN KEY (rule_id) REFERENCES public.alert_rule(rule_id);


-- OpenTenBase baseline: skip FK from replication table refresh_tokens to sys_user.


--
-- Name: progress_report fk289plgq42or3890tc3n1a7nf5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.progress_report
    ADD CONSTRAINT fk289plgq42or3890tc3n1a7nf5 FOREIGN KEY (milestone_id) REFERENCES public.indicator_milestone(id);


--
-- Name: indicator_milestone fk2ygwnq9xx5qug4f7l76fe6sib; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.indicator_milestone
    ADD CONSTRAINT fk2ygwnq9xx5qug4f7l76fe6sib FOREIGN KEY (indicator_id) REFERENCES public.indicator(id);


-- OpenTenBase baseline: skip FK to replication table sys_org.


--
-- Name: alert_window fk4kkplw2s4thxscplhsoon868q; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_window
    ADD CONSTRAINT fk4kkplw2s4thxscplhsoon868q FOREIGN KEY (cycle_id) REFERENCES public.cycle(id);


-- OpenTenBase baseline: skip FK on replication table sys_user -> sys_org.


-- OpenTenBase baseline: skip FK to replication table sys_org.


-- OpenTenBase baseline: skip FK to replication table sys_org.


-- OpenTenBase baseline: skip FK to replication table sys_org.


-- OpenTenBase baseline: skip FK to replication table sys_user.


-- OpenTenBase baseline: skip FK on replication table workflow_task_history -> workflow_task.


-- OpenTenBase baseline: skip FK to replication table sys_user.


-- OpenTenBase baseline: skip FK to replication table sys_org.


-- OpenTenBase baseline: skip FK to replication table sys_org.


--
-- Name: alert_event fkajaeo9e6s7r4vp5a9o8f0u39g; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT fkajaeo9e6s7r4vp5a9o8f0u39g FOREIGN KEY (indicator_id) REFERENCES public.indicator(id);


--
-- Name: indicator fkb2eh8w6br42v1bd7w2g4qdvfd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.indicator
    ADD CONSTRAINT fkb2eh8w6br42v1bd7w2g4qdvfd FOREIGN KEY (parent_indicator_id) REFERENCES public.indicator(id);


--
-- Name: adhoc_task_target fkbca5eqhotyqb4v6nl2ekva80j; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task_target
    ADD CONSTRAINT fkbca5eqhotyqb4v6nl2ekva80j FOREIGN KEY (adhoc_task_id) REFERENCES public.adhoc_task(adhoc_task_id);


--
-- Name: adhoc_task_indicator_map fkbj5sf01egss1wsq1q8y9yg6ag; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task_indicator_map
    ADD CONSTRAINT fkbj5sf01egss1wsq1q8y9yg6ag FOREIGN KEY (indicator_id) REFERENCES public.indicator(id);


-- OpenTenBase baseline: skip FK to replication table sys_org.


-- OpenTenBase baseline: skip FK to replication table sys_org.


--
-- Name: progress_report fkfbpotyeh7xn10q1eo5h74fbjx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.progress_report
    ADD CONSTRAINT fkfbpotyeh7xn10q1eo5h74fbjx FOREIGN KEY (indicator_id) REFERENCES public.indicator(id);


--
-- Name: progress_report fkfk9bq6d7t6xhs7yvoiivp15nc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.progress_report
    ADD CONSTRAINT fkfk9bq6d7t6xhs7yvoiivp15nc FOREIGN KEY (adhoc_task_id) REFERENCES public.adhoc_task(adhoc_task_id);


--
-- Name: adhoc_task fkg29txlpavae5xiyi8hu1188n3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task
    ADD CONSTRAINT fkg29txlpavae5xiyi8hu1188n3 FOREIGN KEY (indicator_id) REFERENCES public.indicator(id);


-- OpenTenBase baseline: skip self-FK on replication table sys_org.


--
-- Name: alert_rule fkjohoca8obstyp3y41pnpdal6v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT fkjohoca8obstyp3y41pnpdal6v FOREIGN KEY (cycle_id) REFERENCES public.cycle(id);


-- OpenTenBase baseline: skip FK to replication table sys_org.


--
-- Name: adhoc_task fkppaqh8pmbjy6khxysj7ntjl8m; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task
    ADD CONSTRAINT fkppaqh8pmbjy6khxysj7ntjl8m FOREIGN KEY (cycle_id) REFERENCES public.cycle(id);


-- OpenTenBase baseline: skip FK to replication table sys_user.


--
-- Name: adhoc_task_indicator_map fkqfjy0x8qt95ucyh6chglogblq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.adhoc_task_indicator_map
    ADD CONSTRAINT fkqfjy0x8qt95ucyh6chglogblq FOREIGN KEY (adhoc_task_id) REFERENCES public.adhoc_task(adhoc_task_id);


--
-- Name: alert_event fkqpvvdt0y5aba9k4tkmpqu2aut; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT fkqpvvdt0y5aba9k4tkmpqu2aut FOREIGN KEY (window_id) REFERENCES public.alert_window(window_id);


--
-- Name: audit_step_instance fks6pd3ndqh2xm71dljjsi67g4t; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_step_instance
    ADD CONSTRAINT fks6pd3ndqh2xm71dljjsi67g4t FOREIGN KEY (instance_id) REFERENCES public.audit_instance(id);


--
-- PostgreSQL database dump complete
--
