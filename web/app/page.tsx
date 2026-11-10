"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { api, ApiError, type Me } from "@/lib/api";
import { homeFor } from "@/lib/session";

export default function Home() {
  const router = useRouter();
  useEffect(() => {
    api
      .get<Me>("/auth/me")
      .then((me) => router.replace(me.mustChangeCredential ? "/change-credential" : homeFor(me.role)))
      .catch((e) => {
        if (e instanceof ApiError && e.status === 401) router.replace("/login");
      });
  }, [router]);
  return <p className="p-8 text-stone-500">BrewFlow…</p>;
}
