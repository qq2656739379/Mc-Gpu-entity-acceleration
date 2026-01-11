// physics.cl - simplified particle physics with gravity, drag, and ground bounce
// Each work-item handles one entity

typedef struct {
    float x, y, z, w; // w padding/alignment
} float4;

__kernel void update_physics(
    __global float4* positions,   // in/out positions
    __global float4* velocities,  // in/out velocities
    const int count,              // entity count
    const float delta_time,       // timestep (seconds)
    const float gravity,          // gravity accel (e.g., -9.8 or -0.08/tick)
    const float ground_y,         // simple ground height for demo
    const float friction          // air drag factor (0..1)
) {
    int i = get_global_id(0);
    if (i >= count) return;

    float4 pos = positions[i];
    float4 vel = velocities[i];

    // apply gravity
    vel.y += gravity * delta_time;

    // apply drag (simplified, per-axis)
    vel.x *= (1.0f - friction);
    vel.z *= (1.0f - friction);

    // integrate position
    float4 new_pos;
    new_pos.x = pos.x + vel.x * delta_time;
    new_pos.y = pos.y + vel.y * delta_time;
    new_pos.z = pos.z + vel.z * delta_time;
    new_pos.w = 0.0f;

    // ground collision / bounce
    if (new_pos.y < ground_y) {
        new_pos.y = ground_y;
        vel.y = -vel.y * 0.5f; // lose 50% energy on bounce
    }

    positions[i] = new_pos;
    velocities[i] = vel;
}
