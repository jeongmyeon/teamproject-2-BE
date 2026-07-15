CREATE DATABASE biddy_member;
\connect biddy_member
CREATE SCHEMA IF NOT EXISTS member_biddy;

CREATE DATABASE biddy_product;
\connect biddy_product
CREATE EXTENSION IF NOT EXISTS vector;

CREATE DATABASE biddy_order;
CREATE DATABASE biddy_auction;
CREATE DATABASE biddy_payment;

CREATE DATABASE biddy_chatbot;
\connect biddy_chatbot
CREATE EXTENSION IF NOT EXISTS vector;
CREATE DATABASE biddy_chat;
CREATE DATABASE biddy_search;
