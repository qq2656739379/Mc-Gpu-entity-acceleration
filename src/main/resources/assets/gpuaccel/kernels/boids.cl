// boids.cl - OpenCL kernel for swarm (Boids) behaviors
// Each work-item handles one entity

typedef struct {
    float x, y, z, w; // w is padding/alignment
} float4;

__kernel void boids_update(
    __global const float4* positions,   // entity positions
    __global const float4* velocities,  // entity velocities
    __global float4* out_forces,        // output forces/acceleration
    const int count,                    // entity count
    const float neighbor_dist,          // neighbor radius
    const float separation_weight,      // separation weight
    const float alignment_weight,       // alignment weight
    const float cohesion_weight         // cohesion weight
) {
    int i = get_global_id(0);
    if (i >= count) return;

    float4 my_pos = positions[i];
    float4 my_vel = velocities[i];

    float3 sep_force = (float3)(0.0f);
    float3 ali_force = (float3)(0.0f);
    float3 coh_center = (float3)(0.0f);
    int neighbors = 0;

    for (int j = 0; j < count; j++) {
        if (i == j) continue; // skip self

        float4 other_pos = positions[j];
        float dist = fast_distance((float3)(my_pos.x, my_pos.y, my_pos.z),
                                   (float3)(other_pos.x, other_pos.y, other_pos.z));

        if (dist < neighbor_dist && dist > 0.001f) {
            // Separation: push away from close neighbors
            float3 push = (float3)(my_pos.x - other_pos.x, my_pos.y - other_pos.y, my_pos.z - other_pos.z);
            sep_force += normalize(push) / dist;

            // Alignment: match neighbor velocity
            ali_force += (float3)(velocities[j].x, velocities[j].y, velocities[j].z);

            // Cohesion: steer toward center
            coh_center += (float3)(other_pos.x, other_pos.y, other_pos.z);

            neighbors++;
        }
    }

    float3 total_force = (float3)(0.0f);

    if (neighbors > 0) {
        if (length(sep_force) > 0)
            total_force += normalize(sep_force) * separation_weight;

        ali_force /= (float)neighbors;
        if (length(ali_force) > 0)
            total_force += normalize(ali_force) * alignment_weight;

        coh_center /= (float)neighbors;
        float3 to_center = coh_center - (float3)(my_pos.x, my_pos.y, my_pos.z);
        if (length(to_center) > 0)
            total_force += normalize(to_center) * cohesion_weight;
    }

    out_forces[i] = (float4)(total_force.x, total_force.y, total_force.z, 0.0f);
}
