use toml_writer::ToTomlValue;
struct Rng(u64);
impl Rng { fn next(&mut self) -> u64 { let mut x = self.0; x ^= x << 13; x ^= x >> 7; x ^= x << 17; self.0 = x; x } }
fn main() {
    let mut out = String::new();
    let mut curated: Vec<f64> = vec![0.0, -0.0, 1.0, -1.0, 0.1, 0.2, 0.3, 1.5, 3.14, 1e15, 1e16, 1e17, 1e21, 1e22, 1e23, 1e100,
        f64::MIN_POSITIVE, f64::EPSILON, 5e-324, 2.2250738585072009e-308, 1e-5, 1e-4, 1e-7, 123456789.0, 9007199254740993.0,
        0.000001, 4.9406564584124654e-324, 2.0f64.powi(63), 2.0f64.powi(64), 100.0, 6.02214076e23, -2.5e-10, 0.1 + 0.2,
        f64::INFINITY, f64::NEG_INFINITY, f64::NAN, -f64::NAN];
    for e in -30..30 { curated.push(10f64.powi(e)); curated.push(1.5 * 10f64.powi(e)); }
    for i in 0..100u64 { curated.push((i as f64) / 7.0); curated.push(i as f64 * 1e10 + 0.5); }
    for v in &curated {
        out.push_str(&format!("D {:016x} {} {:?} {}\n", v.to_bits(), v, v, v.to_toml_value()));
    }
    let mut rng = Rng(0x9E3779B97F4A7C15);
    let mut randoms: Vec<f64> = vec![f64::MAX, f64::MIN, 1.7976931348623157e308, 1e300, 1e308];
    for _ in 0..1500 { randoms.push(f64::from_bits(rng.next())); }
    for v in &randoms { out.push_str(&format!("d {:016x} {:e} {:?}\n", v.to_bits(), v, v)); }
    let mut rng = Rng(12345);
    let mut f32s: Vec<f32> = vec![0.0, -0.0, 0.1, 1.0, 1.5, f32::MAX, f32::MIN_POSITIVE, 1e-45, 16777217.0, 1e10];
    for _ in 0..500 { f32s.push(f32::from_bits(rng.next() as u32)); }
    for v in &f32s { out.push_str(&format!("f {:08x} {:e} {:?}\n", v.to_bits(), v, v)); }
    let short: Vec<f32> = vec![0.0, -0.0, 0.1, 1.0, 1.5, 16777217.0, 1e10, 3.4e-5, f32::INFINITY, f32::NAN];
    for v in &short { out.push_str(&format!("F {:08x} {} {}\n", v.to_bits(), v, v.to_toml_value())); }
    print!("{out}");
}
