use jni::objects::{JBooleanArray, JClass, JDoubleArray, JByteBuffer};
use jni::sys::{jboolean, jbooleanArray, jdoubleArray, jintArray};
use jni::objects::{JObject};
use jni::sys::{jobject, jint, jdouble};
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
    mut env: JNIEnv,
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
    let len_d = match env.get_array_length(&distances) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances length error: {}", e)); return std::ptr::null_mut(); } } as usize;
    let len_s = match env.get_array_length(&speeds) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("speeds length error: {}", e)); return std::ptr::null_mut(); } } as usize;
    let len_c = match env.get_array_length(&cos_angles) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("cosAngles length error: {}", e)); return std::ptr::null_mut(); } } as usize;
    if len_d == 0 || len_d != len_s || len_d != len_c {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Array lengths do not match or zero length");
        return std::ptr::null_mut();
    }

    let mut d = vec![0f64; len_d];
    let mut s = vec![0f64; len_d];
    let mut c = vec![0f64; len_d];
    if let Err(e) = env.get_double_array_region(&distances, 0, &mut d) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances read error: {}", e)); return std::ptr::null_mut(); }
    if let Err(e) = env.get_double_array_region(&speeds, 0, &mut s) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("speeds read error: {}", e)); return std::ptr::null_mut(); }
    if let Err(e) = env.get_double_array_region(&cos_angles, 0, &mut c) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("cosAngles read error: {}", e)); return std::ptr::null_mut(); }

    let mut out_vec: Vec<jboolean> = Vec::with_capacity(len_d);
    unsafe { out_vec.set_len(len_d); }
    for i in 0..len_d {
        let cull = d[i] > max_distance && s[i] < speed_threshold && c[i] < cos_angle_threshold;
        out_vec[i] = if cull { 1 } else { 0 };
    }
    let arr = match env.new_boolean_array(len_d as i32) { Ok(a) => a, Err(e) => { let _ = env.throw_new("java/lang/RuntimeException", format!("allocate boolean[] failed: {}", e)); return std::ptr::null_mut(); } };
    if let Err(e) = env.set_boolean_array_region(&arr, 0, &out_vec) {
        let _ = env.throw_new("java/lang/RuntimeException", format!("write boolean[] failed: {}", e));
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_id_rnggagib_nativebridge_NativeCulling_shouldCullBatchInto(
    mut env: JNIEnv,
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

    let len = match env.get_array_length(&distances) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances length error: {}", e)); return; } } as usize;
    if len == 0 { let _ = env.throw_new("java/lang/IllegalArgumentException", "Zero length distances"); return; }
    if env.get_array_length(&speeds).ok().map(|x| x as usize) != Some(len) { let _ = env.throw_new("java/lang/IllegalArgumentException", "speeds length mismatch"); return; }
    if env.get_array_length(&cos_angles).ok().map(|x| x as usize) != Some(len) { let _ = env.throw_new("java/lang/IllegalArgumentException", "cosAngles length mismatch"); return; }
    if env.get_array_length(&out_flags).ok().map(|x| x as usize) != Some(len) { let _ = env.throw_new("java/lang/IllegalArgumentException", "outFlags length mismatch"); return; }

    let mut d = vec![0f64; len];
    let mut s = vec![0f64; len];
    let mut c = vec![0f64; len];
    if let Err(e) = env.get_double_array_region(&distances, 0, &mut d) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances read error: {}", e)); return; }
    if let Err(e) = env.get_double_array_region(&speeds, 0, &mut s) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("speeds read error: {}", e)); return; }
    if let Err(e) = env.get_double_array_region(&cos_angles, 0, &mut c) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("cosAngles read error: {}", e)); return; }

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
    if let Err(e) = env.set_boolean_array_region(&out_flags, 0, &out) { let _ = env.throw_new("java/lang/RuntimeException", format!("write boolean[] failed: {}", e)); }
        return;
    }

    // Scalar fallback
    let mut out: Vec<jboolean> = vec![0; len];
    for i in 0..len {
        out[i] = if d[i] > max_distance && s[i] < speed_threshold && c[i] < cos_angle_threshold { 1 } else { 0 };
    }
    let _ = env.set_boolean_array_region(&out_flags, 0, &out);
}

#[no_mangle]
pub extern "system" fn Java_id_rnggagib_nativebridge_NativeCulling_shouldCullBatchIntoByType(
    mut env: JNIEnv,
    _class: JClass,
    distances_raw: jdoubleArray,
    speeds_raw: jdoubleArray,
    cos_angles_raw: jdoubleArray,
    type_codes_raw: jintArray,
    type_max_distance_raw: jdoubleArray,
    type_speed_threshold_raw: jdoubleArray,
    type_cos_threshold_raw: jdoubleArray,
    out_flags_raw: jbooleanArray,
) {
    let distances = unsafe { JDoubleArray::from_raw(distances_raw) };
    let speeds = unsafe { JDoubleArray::from_raw(speeds_raw) };
    let cos_angles = unsafe { JDoubleArray::from_raw(cos_angles_raw) };
    let type_codes = unsafe { jni::objects::JIntArray::from_raw(type_codes_raw) };
    let type_md = unsafe { JDoubleArray::from_raw(type_max_distance_raw) };
    let type_st = unsafe { JDoubleArray::from_raw(type_speed_threshold_raw) };
    let type_ct = unsafe { JDoubleArray::from_raw(type_cos_threshold_raw) };
    let out_flags = unsafe { JBooleanArray::from_raw(out_flags_raw) };

    let len = match env.get_array_length(&distances) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances length error: {}", e)); return; } } as usize;
    if len == 0 { let _ = env.throw_new("java/lang/IllegalArgumentException", "Zero length distances"); return; }
    if env.get_array_length(&speeds).ok().map(|x| x as usize) != Some(len) { let _ = env.throw_new("java/lang/IllegalArgumentException", "speeds length mismatch"); return; }
    if env.get_array_length(&cos_angles).ok().map(|x| x as usize) != Some(len) { let _ = env.throw_new("java/lang/IllegalArgumentException", "cosAngles length mismatch"); return; }
    if env.get_array_length(&type_codes).ok().map(|x| x as usize) != Some(len) { let _ = env.throw_new("java/lang/IllegalArgumentException", "typeCodes length mismatch"); return; }
    if env.get_array_length(&out_flags).ok().map(|x| x as usize) != Some(len) { let _ = env.throw_new("java/lang/IllegalArgumentException", "outFlags length mismatch"); return; }

    let type_md_len = match env.get_array_length(&type_md) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("type_md length error: {}", e)); return; } } as usize;
    let type_st_len = match env.get_array_length(&type_st) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("type_st length error: {}", e)); return; } } as usize;
    let type_ct_len = match env.get_array_length(&type_ct) { Ok(l) => l, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("type_ct length error: {}", e)); return; } } as usize;
    if type_md_len == 0 || type_st_len != type_md_len || type_ct_len != type_md_len { let _ = env.throw_new("java/lang/IllegalArgumentException", "type thresholds length mismatch or zero"); return; }

    let mut d = vec![0f64; len];
    let mut s = vec![0f64; len];
    let mut c = vec![0f64; len];
    let mut t = vec![0i32; len];
    if let Err(e) = env.get_double_array_region(&distances, 0, &mut d) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances read error: {}", e)); return; }
    if let Err(e) = env.get_double_array_region(&speeds, 0, &mut s) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("speeds read error: {}", e)); return; }
    if let Err(e) = env.get_double_array_region(&cos_angles, 0, &mut c) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("cosAngles read error: {}", e)); return; }
    if let Err(e) = env.get_int_array_region(&type_codes, 0, &mut t) { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("typeCodes read error: {}", e)); return; }

    let mut md = vec![0f64; type_md_len];
    let mut st = vec![0f64; type_st_len];
    let mut ct = vec![0f64; type_ct_len];
    if env.get_double_array_region(&type_md, 0, &mut md).is_err() { return; }
    if env.get_double_array_region(&type_st, 0, &mut st).is_err() { return; }
    if env.get_double_array_region(&type_ct, 0, &mut ct).is_err() { return; }

    let mut out: Vec<jboolean> = vec![0; len];
    for i in 0..len {
        let code = t[i] as usize;
        if code >= type_md_len { continue; }
        let cull = d[i] > md[code] && s[i] < st[code] && c[i] < ct[code];
        out[i] = if cull { 1 } else { 0 };
    }
    if let Err(e) = env.set_boolean_array_region(&out_flags, 0, &out) { let _ = env.throw_new("java/lang/RuntimeException", format!("write boolean[] failed: {}", e)); }
}

// DirectByteBuffer variant: inputs are direct ByteBuffers with f64 data; out is direct ByteBuffer of u8 flags (0/1)
#[no_mangle]
pub extern "system" fn Java_id_rnggagib_nativebridge_NativeCulling_shouldCullBatchIntoDirect(
    mut env: JNIEnv,
    _class: JClass,
    distances_buf: jobject,
    speeds_buf: jobject,
    cos_buf: jobject,
    out_buf: jobject,
    count: jint,
    max_distance: jdouble,
    speed_threshold: jdouble,
    cos_angle_threshold: jdouble,
) {
    let cnt = if count <= 0 { let _ = env.throw_new("java/lang/IllegalArgumentException", "count must be > 0"); return; } else { count as usize };
    // Safety: Treat ByteBuffers as raw byte slices, then reinterpret as f64
    let distances_obj = unsafe { JObject::from_raw(distances_buf) };
    let speeds_obj = unsafe { JObject::from_raw(speeds_buf) };
    let cos_obj = unsafe { JObject::from_raw(cos_buf) };
    let out_obj = unsafe { JObject::from_raw(out_buf) };

    let distances_bb: JByteBuffer = distances_obj.into();
    let speeds_bb: JByteBuffer = speeds_obj.into();
    let cos_bb: JByteBuffer = cos_obj.into();
    let out_bb: JByteBuffer = out_obj.into();

    let distances_ptr = match env.get_direct_buffer_address(&distances_bb) { Ok(p) => p, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances buffer addr: {}", e)); return; } };
    let speeds_ptr = match env.get_direct_buffer_address(&speeds_bb) { Ok(p) => p, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("speeds buffer addr: {}", e)); return; } };
    let cos_ptr = match env.get_direct_buffer_address(&cos_bb) { Ok(p) => p, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("cos buffer addr: {}", e)); return; } };
    let out_ptr = match env.get_direct_buffer_address(&out_bb) { Ok(p) => p, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("out buffer addr: {}", e)); return; } };

    let distances_cap = match env.get_direct_buffer_capacity(&distances_bb) { Ok(c) => c as usize, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("distances buffer cap: {}", e)); return; } };
    let speeds_cap = match env.get_direct_buffer_capacity(&speeds_bb) { Ok(c) => c as usize, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("speeds buffer cap: {}", e)); return; } };
    let cos_cap = match env.get_direct_buffer_capacity(&cos_bb) { Ok(c) => c as usize, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("cos buffer cap: {}", e)); return; } };
    let out_cap = match env.get_direct_buffer_capacity(&out_bb) { Ok(c) => c as usize, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", format!("out buffer cap: {}", e)); return; } };

    let need_dbytes = cnt * std::mem::size_of::<f64>();
    if distances_cap < need_dbytes || speeds_cap < need_dbytes || cos_cap < need_dbytes || out_cap < cnt {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Direct buffers too small for requested count");
        return;
    }

    let distances: &[f64] = unsafe { std::slice::from_raw_parts(distances_ptr as *const f64, cnt) };
    let speeds: &[f64] = unsafe { std::slice::from_raw_parts(speeds_ptr as *const f64, cnt) };
    let cos: &[f64] = unsafe { std::slice::from_raw_parts(cos_ptr as *const f64, cnt) };
    let out: &mut [u8] = unsafe { std::slice::from_raw_parts_mut(out_ptr as *mut u8, cnt) };

    let md = max_distance as f64;
    let st = speed_threshold as f64;
    let ct = cos_angle_threshold as f64;
    for i in 0..cnt {
        let cull = distances[i] > md && speeds[i] < st && cos[i] < ct;
        out[i] = if cull { 1 } else { 0 };
    }
}
 
