CREATE TYPE status AS ENUM ('pending', 'inprogress', 'redo', 'failed', 'completed');

CREATE TABLE jobs (
  id serial,
  job json NOT NULL,
  retries int,
  retries_timeout int,
  tags varchar(255)[],
  status status NOT NULL,
  do_at timestamp with time zone,
  taked_at timestamp with time zone,
  created_at timestamp with time zone NOT NULL,
  completed_at timestamp with time zone
);

CREATE INDEX to_take on jobs(do_at) WHERE status = 'pending' or status = 'redo';
CREATE INDEX tags on jobs USING GIN (tags);
-- CREATE INDEX job on jobs USING GIN (to_tsvector('english', cast(job AS text)));
CREATE INDEX status on jobs(status);

create extension pg_trgm;
CREATE INDEX job ON jobs USING GIN ((job::text) gin_trgm_ops);
