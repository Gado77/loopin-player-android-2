import { createClient } from "@supabase/supabase-js";
import { authOptions } from "./auth-config";
import { config } from "./config";

export const supabase = createClient(config.supabaseUrl, config.supabaseAnonKey, {
  auth: authOptions,
});
