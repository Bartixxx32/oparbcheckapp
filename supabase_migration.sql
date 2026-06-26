-- Add app_version column to devices table
ALTER TABLE devices ADD COLUMN IF NOT EXISTS app_version TEXT DEFAULT '0.0.0';
