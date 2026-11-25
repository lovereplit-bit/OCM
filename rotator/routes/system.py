import asyncio
import json
import logging
import os
import time
from pathlib import Path
from typing import Any, Dict, List
from urllib.request import Request as UrlRequest, urlopen

from fastapi import APIRouter, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from core.config import WARP_ROTATOR_URL
from core.db import get_recent_ips, load_all_usage
from core.metrics import STARTUP_TIME, metrics
from rotator import get_ip_location, get_public_ip, rotate_warp, rotation_count
from services.discovery import get_discovered_models
from services.proxy_pool import get_next_outbound_proxy, get_proxy_count, save_proxy_list

log = logging.getLogger("routes.system")
router = APIRouter()
templates = Jinja2Templates(directory=str(Path(__file__).resolve().parent.parent / "templates"))


def _trigger_rotation_sync() -> Dict[str, Any]:
    for endpoint in (f"{WARP_ROTATOR_URL.rstrip('/')}/rotate", "http://127.0.0.1:8001/rotate"):
        try:
            req = UrlRequest(endpoint, data=b"", headers={"User-Agent": "OpenCode-Proxy/2.0"}, method="POST")
            with urlopen(req, timeout=20) as resp:
                if resp.status == 200:
                    payload = json.loads(resp.read().decode("utf-8", errors="ignore") or "{}")
                    if payload.get("status") == "success":
                        return payload
        except Exception:
            continue

    if rotate_warp(reason="Manual Proxy /rotate Trigger", force=True):
        ip = get_public_ip()
        return {"status": "success", "verified_ip": ip}

    nxt = get_next_outbound_proxy()
    ip = get_public_ip()
    if nxt:
        return {"status": "success", "verified_ip": ip, "proxy_rotated": True}
    return {"status": "failed", "verified_ip": ip}


@router.get("/health")
@router.get("/status")
async def health_check():
    models = get_discovered_models()
    return {
        "status": "healthy",
        "timestamp": int(time.time()),
        "uptime_seconds": int(time.time() - STARTUP_TIME),
        "discovered_models_count": len(models),
        "proxy_pool_count": get_proxy_count(),
    }


@router.post("/rotate")
async def trigger_rotate():
    res = await asyncio.to_thread(_trigger_rotation_sync)
    code = 200 if res.get("status") == "success" else 503
    return JSONResponse(status_code=code, content=res)


@router.get("/proxies")
async def get_proxies_info():
    return {"count": get_proxy_count()}


@router.post("/proxies")
async def update_proxies(request: Request):
    try:
        body = await request.json()
    except Exception:
        body = {}
    raw = body.get("proxies", body.get("list", []))
    items: List[str] = raw if isinstance(raw, list) else [str(raw or "")]
    pool = await asyncio.to_thread(save_proxy_list, items)
    return {"ok": True, "count": len(pool)}


@router.get("/metrics")
async def get_metrics(request: Request):
    accept = request.headers.get("accept", "")
    if "text/plain" in accept or "openmetrics" in accept:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    # JSON metrics for test.py and dashboard
    ip = get_public_ip()
    loc = get_ip_location(ip) if ip and ip != "Unknown" else {}
    models = get_discovered_models()
    return {
        "total_requests": metrics["total_requests"],
        "successful_requests": metrics["successful_requests"],
        "rate_limited_requests": metrics["rate_limited_requests"],
        "failed_requests": metrics["failed_requests"],
        "rotation_count": rotation_count,
        "verified_public_ip": ip,
        "location": loc,
        "uptime_seconds": int(time.time() - STARTUP_TIME),
        "discovered_models": [m["id"] for m in models],
        "proxy_pool_count": get_proxy_count(),
    }


@router.get("/dashboard", response_class=HTMLResponse)
async def dashboard(request: Request):
    return templates.TemplateResponse(
        "dashboard.html",
        {"request": request, "metrics": metrics},
    )


@router.get("/dashboard/stats")
async def dashboard_stats():
    ip = get_public_ip()
    loc = get_ip_location(ip) if ip and ip != "Unknown" else {}
    models = get_discovered_models()
    return {
        "verified_ip": ip,
        "location": loc,
        "total_requests": metrics["total_requests"],
        "successful_requests": metrics["successful_requests"],
        "rate_limited_requests": metrics["rate_limited_requests"],
        "rotation_count": rotation_count,
        "uptime_seconds": int(time.time() - STARTUP_TIME),
        "active_models_count": len(models),
        "models": models,
        "model_usage": load_all_usage(),
        "recent_ips": get_recent_ips(limit=10),
        "proxy_pool_count": get_proxy_count(),
    }
