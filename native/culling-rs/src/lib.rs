use jni::objects::{JBooleanArray, JClass, JDoubleArray};
use jni::sys::{jboolean, jbooleanArray, jdoubleArray};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_id_rnggagib_nativebridge_NativeCulling_shouldCull(
    _env: JNIEnv,
    _class: JClass,
    distance: f64,
    speed: f64,
    cos_angle: f64,
    max_distance: f64,
    speed_threshold: f64,
    cos_angle_threshold: f64,
) -> jboolean {
    // Simple, branch-light heuristic suitable for SIMD in future
    let cull = distance > max_distance && speed < speed_threshold && cos_angle < cos_angle_threshold;
    if cull { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_id_rnggagib_nativebridge_NativeCulling_shouldCullBatch(
    env: JNIEnv,
    _class: JClass,
    distances_raw: jdoubleArray,
    speeds_raw: jdoubleArray,
    cos_angles_raw: jdoubleArray,
    max_distance: f64,
    speed_threshold: f64,
    cos_angle_threshold: f64,
) -> jbooleanArray {
    let distances = unsafe { JDoubleArray::from_raw(distances_raw) };
    let speeds = unsafe { JDoubleArray::from_raw(speeds_raw) };
    let cos_angles = unsafe { JDoubleArray::from_raw(cos_angles_raw) };
    let len_d = match env.get_array_length(&distances) { Ok(l) => l, Err(_) => return std::ptr::null_mut() } as usize;
    let len_s = match env.get_array_length(&speeds) { Ok(l) => l, Err(_) => return std::ptr::null_mut() } as usize;
    let len_c = match env.get_array_length(&cos_angles) { Ok(l) => l, Err(_) => return std::ptr::null_mut() } as usize;
    if len_d == 0 || len_d != len_s || len_d != len_c {
        return std::ptr::null_mut();
    }

    let mut d = vec![0f64; len_d];
    let mut s = vec![0f64; len_d];
    let mut c = vec![0f64; len_d];
    if env.get_double_array_region(&distances, 0, &mut d).is_err() { return std::ptr::null_mut(); }
    if env.get_double_array_region(&speeds, 0, &mut s).is_err() { return std::ptr::null_mut(); }
    if env.get_double_array_region(&cos_angles, 0, &mut c).is_err() { return std::ptr::null_mut(); }

    let mut out_vec: Vec<jboolean> = Vec::with_capacity(len_d);
    unsafe { out_vec.set_len(len_d); }
    for i in 0..len_d {
        let cull = d[i] > max_distance && s[i] < speed_threshold && c[i] < cos_angle_threshold;
        out_vec[i] = if cull { 1 } else { 0 };
    }
    let arr = match env.new_boolean_array(len_d as i32) { Ok(a) => a, Err(_) => return std::ptr::null_mut() };
    if env.set_boolean_array_region(&arr, 0, &out_vec).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_id_rnggagib_nativebridge_NativeCulling_shouldCullBatchInto(
    env: JNIEnv,
    _class: JClass,
    distances_raw: jdoubleArray,
    speeds_raw: jdoubleArray,
    cos_angles_raw: jdoubleArray,
    out_flags_raw: jbooleanArray,
    max_distance: f64,
    speed_threshold: f64,
    cos_angle_threshold: f64,
) {
    let distances = unsafe { JDoubleArray::from_raw(distances_raw) };
    let speeds = unsafe { JDoubleArray::from_raw(speeds_raw) };
    let cos_angles = unsafe { JDoubleArray::from_raw(cos_angles_raw) };
    let out_flags = unsafe { JBooleanArray::from_raw(out_flags_raw) };

    let len = match env.get_array_length(&distances) { Ok(l) => l, Err(_) => return } as usize;
    if len == 0 { return; }
    if env.get_array_length(&speeds).ok().map(|x| x as usize) != Some(len) { return; }
    if env.get_array_length(&cos_angles).ok().map(|x| x as usize) != Some(len) { return; }
    if env.get_array_length(&out_flags).ok().map(|x| x as usize) != Some(len) { return; }

    let mut d = vec![0f64; len];
    let mut s = vec![0f64; len];
    let mut c = vec![0f64; len];
    if env.get_double_array_region(&distances, 0, &mut d).is_err() { return; }
    if env.get_double_array_region(&speeds, 0, &mut s).is_err() { return; }
    if env.get_double_array_region(&cos_angles, 0, &mut c).is_err() { return; }

    #[cfg(feature = "simd")]
    {
        use wide::f64x4;
        let mut out: Vec<jboolean> = vec![0; len];
        let mut i = 0usize;
        while i + 4 <= len {
            let vd = f64x4::from_slice_unaligned(&d[i..]);
            let vs = f64x4::from_slice_unaligned(&s[i..]);
            let vc = f64x4::from_slice_unaligned(&c[i..]);
            let md = f64x4::splat(max_distance);
            let st = f64x4::splat(speed_threshold);
            let ct = f64x4::splat(cos_angle_threshold);
            let mask = (vd.gt(md) & vs.lt(st) & vc.lt(ct)).to_int().to_array();
            for k in 0..4 { out[i + k] = if mask[k] != 0 { 1 } else { 0 }; }
            i += 4;
        }
        while i < len {
            out[i] = if d[i] > max_distance && s[i] < speed_threshold && c[i] < cos_angle_threshold { 1 } else { 0 };
            i += 1;
        }
    let _ = env.set_boolean_array_region(&out_flags, 0, &out);
        return;
    }

    // Scalar fallback
    let mut out: Vec<jboolean> = vec![0; len];
    for i in 0..len {
        out[i] = if d[i] > max_distance && s[i] < speed_threshold && c[i] < cos_angle_threshold { 1 } else { 0 };
    }
    let _ = env.set_boolean_array_region(&out_flags, 0, &out);
}
