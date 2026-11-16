//! 真实 EPG 端到端 smoke：下载 https://epg.laobaitv.net（gzip），
//! 解压后通过 Rust grid builder 生成网格。
//!
//! 运行： cargo run -p iqtvlite-capi --example epg_smoke

use iqtvlite_epg::{build_grid, GridConfig};
use std::io::Read;

fn main() {
    // 用 std::process curl 下载（CI 和 Windows 都有），避免额外依赖
    let out = std::process::Command::new("curl")
        .args(["-sL", "--compressed", "https://epg.laobaitv.net"])
        .output()
        .expect("curl failed");
    let raw = out.stdout;
    eprintln!("downloaded {} bytes", raw.len());

    // 检测 gzip magic (1f 8b)
    let xml_bytes = if raw.len() > 2 && raw[0] == 0x1f && raw[1] == 0x8b {
        use flate2::read::GzDecoder;
        let mut s = String::new();
        GzDecoder::new(&raw[..]).read_to_string(&mut s).unwrap();
        s.into_bytes()
    } else {
        raw
    };
    let xml = String::from_utf8_lossy(&xml_bytes);
    eprintln!("xml chars: {}", xml.len());

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64;
    let grid = build_grid(
        &xml,
        &GridConfig {
            window_start: now - 1800,
            window_end: now + 6 * 3600,
            slot_minutes: 30,
            tvg_ids: vec![
                "CCTV1".into(),
                "CCTV5".into(),
                "CCTV9".into(),
                "HunanTV".into(),
            ],
            max_rows: 50,
        },
        now,
    );

    println!("网格统计: {:?}", grid.stats);
    for row in &grid.rows {
        println!(
            "  [{}] {} — {} 个节目",
            row.tvg_id,
            row.name,
            row.programmes.len()
        );
        for p in row.programmes.iter().take(3) {
            println!(
                "      {} (col={}, span={}, progress={}%)",
                p.title, p.col, p.span, p.progress
            );
        }
    }
}
