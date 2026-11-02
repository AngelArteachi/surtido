-- One database per service on a shared server: no service reads another's tables.
CREATE DATABASE catalog;
CREATE DATABASE orders;
CREATE DATABASE payments;
CREATE DATABASE shipping;
CREATE DATABASE notifications;
