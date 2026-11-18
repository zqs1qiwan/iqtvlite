//! File-based XMLTV bridge. XML and gzip remain outside the Java heap.
use std::collections::BTreeMap;
use std::io::{self, BufRead, BufReader, Read};

const MAX_XML_BYTES: usize = 32 * 1024 * 1024;
struct Budget<R> {
    inner: R,
    remaining: usize,
}
impl<R: BufRead> Read for Budget<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        let bytes = self.fill_buf()?;
        let n = bytes.len().min(out.len());
        out[..n].copy_from_slice(&bytes[..n]);
        self.consume(n);
        Ok(n)
    }
}
impl<R: BufRead> BufRead for Budget<R> {
    fn fill_buf(&mut self) -> io::Result<&[u8]> {
        let bytes = self.inner.fill_buf()?;
        if self.remaining == 0 && !bytes.is_empty() {
            return Err(io::Error::other("XMLTV exceeds 32 MiB"));
        }
        Ok(&bytes[..bytes.len().min(self.remaining)])
    }
    fn consume(&mut self, amount: usize) {
        self.remaining -= amount;
        self.inner.consume(amount);
    }
}

use iqtvlite_core::parse::xmltv::parse_reader;
use serde_json::{json, Value};

pub fn parse_guide<R: BufRead>(reader: R) -> Value {
    let mut channels = Vec::new();
    let mut programmes: BTreeMap<String, Vec<Value>> = BTreeMap::new();
    let mut count = 0usize;
    let result = parse_reader(
        Budget {
            inner: reader,
            remaining: MAX_XML_BYTES,
        },
        |channel| {
            if channels.len() >= 10000 {
                return Err(io::Error::other("too many XMLTV channels").into());
            }
            channels.push(json!([channel.id, channel.display_names]));
            Ok(())
        },
        |p| {
            count += 1;
            if count > 100000 {
                return Err(io::Error::other("too many XMLTV programmes").into());
            }
            if let Some(title) = p.title.filter(|t| !t.trim().is_empty()) {
                let stop = if p.stop_ts < p.start_ts {
                    p.stop_ts + 86400
                } else {
                    p.stop_ts
                };
                if stop > p.start_ts {
                    programmes.entry(p.channel).or_default().push(json!([
                        p.start_ts * 1000,
                        stop * 1000,
                        title,
                        p.source_offset_seconds
                    ]));
                }
            }
            Ok(())
        },
    );
    match result {
        Ok(stats) if !programmes.is_empty() => {
            for rows in programmes.values_mut() {
                rows.sort_by_key(|row| row[0].as_i64().unwrap_or(0));
            }
            json!({"ok": true, "channels": channels, "programmes": programmes, "count": stats.programmes})
        }
        Ok(_) => json!({"ok": false, "error": "XMLTV has no valid programmes"}),
        Err(e) => json!({"ok": false, "error": e.to_string()}),
    }
}

pub fn parse_file(path: &str) -> Value {
    let Ok(file) = std::fs::File::open(path) else {
        return json!({"ok": false, "error": "cannot open XMLTV cache"});
    };
    let mut reader = BufReader::new(file);
    let gzip = reader
        .fill_buf()
        .map(|b| b.starts_with(&[0x1f, 0x8b]))
        .unwrap_or(false);
    if gzip {
        parse_guide(BufReader::new(flate2::read::GzDecoder::new(reader)))
    } else {
        parse_guide(reader)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    const XML: &str = r#"<tv><channel id="c1"><display-name>中文频道</display-name></channel><programme channel="c1" start="20261118080000 +0800" stop="20261118090000 +0800"><title>早间新闻</title></programme></tv>"#;

    #[test]
    fn guide_retains_utc_offset_and_chinese() {
        let value = parse_guide(XML.as_bytes());
        assert_eq!(value["ok"], true);
        assert_eq!(value["channels"][0][1][0], "中文频道");
        assert_eq!(value["programmes"]["c1"][0][2], "早间新闻");
        assert_eq!(value["programmes"]["c1"][0][3], 28800);
        assert_eq!(
            value["programmes"]["c1"][0][1].as_i64().unwrap()
                - value["programmes"]["c1"][0][0].as_i64().unwrap(),
            3600000
        );
    }

    #[test]
    fn budget_rejects_expanded_input_and_closes_reader() {
        let mut reader = Budget {
            inner: b"abc".as_slice(),
            remaining: 2,
        };
        let mut bytes = Vec::new();
        assert!(reader.read_to_end(&mut bytes).is_err());
        assert_eq!(bytes, b"ab");
    }

    #[test]
    fn file_bridge_decodes_bare_gzip_and_rejects_bad_xml() {
        let mut gzip = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        gzip.write_all(XML.as_bytes()).unwrap();
        let bytes = gzip.finish().unwrap();
        let path = std::env::temp_dir().join(format!("iqtv-guide-{}.gz", std::process::id()));
        std::fs::write(&path, bytes).unwrap();
        assert_eq!(parse_file(path.to_str().unwrap())["ok"], true);
        std::fs::remove_file(path).unwrap();
        assert_eq!(
            parse_guide(b"<html>not xmltv</html>".as_slice())["ok"],
            false
        );
    }
}
