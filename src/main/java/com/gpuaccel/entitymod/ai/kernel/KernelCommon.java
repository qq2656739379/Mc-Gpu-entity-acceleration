package com.gpuaccel.entitymod.ai.kernel;

public class KernelCommon {
    public static final String SRC = """
        #define STATE_IDLE 0
        #define STATE_GATHER 1
        #define STATE_RETURN 2
        #define STATE_HIVE 3
        
        #define TYPE_FLYER 0
        #define TYPE_ITEM 1
        #define TYPE_XP 2
        #define TYPE_QUEEN 3
        #define TYPE_WALKER 4
        #define TYPE_SWIMMER 5

        #define VOXEL_AIR 0
        #define VOXEL_SOLID 1
        #define VOXEL_LIQUID 2
        
        #define PI 3.14159265f

        float3 hash33(float3 p) {
            p = (float3)(dot(p, (float3)(127.1f, 311.7f, 74.7f)),
                         dot(p, (float3)(269.5f, 183.3f, 246.1f)),
                         dot(p, (float3)(113.5f, 271.9f, 124.6f)));
            float3 s = sin(p) * 43758.5453123f;
            return -1.0f + 2.0f * (s - floor(s));
        }
        
        uint next_rand(uint state) {
            return state * 1664525u + 1013904223u;
        }
        
        // 🛠️ 修复: 安全归一化，防止除以零产生 NaN
        float3 safe_normalize(float3 v) {
            float lenSq = dot(v, v);
            if (lenSq < 1e-8f) return (float3)(0.0f);
            return v * rsqrt(lenSq);
        }

        float3 curl_noise(float3 p, float time) {
            float e = 0.1f;
            float3 p_t = p + (float3)(0, time * 0.5f, 0);
            float3 n0 = hash33(p_t);
            float3 dx = hash33(p_t + (float3)(e, 0, 0));
            float3 dy = hash33(p_t + (float3)(0, e, 0));
            float3 dz = hash33(p_t + (float3)(0, 0, e));
            float x = (dy.z - n0.z) - (dz.y - n0.y);
            float y = (dz.x - n0.x) - (dx.z - n0.z);
            float z = (dx.y - n0.y) - (dy.x - n0.x);
            float3 v = (float3)(x, y, z);
            return safe_normalize(v); // 使用 safe_normalize
        }

        float3 limit_vec(float3 v, float maxVal) {
            float lenSq = dot(v, v);
            if (lenSq > maxVal * maxVal && lenSq > 1e-6f) {
                return v * (maxVal * rsqrt(lenSq));
            }
            return v;
        }
        
        float3 steer(float3 currVel, float3 targetVel, float maxForce, float mass) {
            float3 desired = targetVel;
            float3 steering = desired - currVel;
            steering = limit_vec(steering, maxForce);
            return steering / mass; 
        }

        bool in_fov(float3 fwd, float3 diff, float fovCos) {
            float3 dir = safe_normalize(diff); // 使用 safe_normalize
            return dot(fwd, dir) > fovCos;
        }
        
        // ... (get_voxel, is_solid, cast_ray, get_fibonacci_cone 保持不变)
        // 为节省篇幅省略，请确保保留原文件中的这些辅助函数
        
        float3 rotate_y(float3 v, float ang) {
            float c = cos(ang);
            float s = sin(ang);
            return (float3)(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
        }

        char get_voxel(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            int ix = (int)floor(p.x) - oX;
            int iy = (int)floor(p.y) - oY;
            int iz = (int)floor(p.z) - oZ;
            if (ix >= 0 && ix < size && iy >= 0 && iy < size && iz >= 0 && iz < size) {
                return voxels[ix + iz*size + iy*size*size];
            }
            // 越界时默认返回空气，避免离开地图边界生物被判定为“嵌入固体”导致取消重力
            return VOXEL_AIR; 
        }
        
        bool is_solid(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            return get_voxel(p, voxels, oX, oY, oZ, size) == VOXEL_SOLID;
        }

        float cast_ray(float3 start, float3 dir, float maxDist, __global const char* voxels, int oX, int oY, int oZ, int size) {
            int mx = (int)floor(start.x); int my = (int)floor(start.y); int mz = (int)floor(start.z);
            float3 dDist = (float3)(fabs(1.0f/dir.x), fabs(1.0f/dir.y), fabs(1.0f/dir.z));
            int stepX = dir.x < 0 ? -1 : 1; int stepY = dir.y < 0 ? -1 : 1; int stepZ = dir.z < 0 ? -1 : 1;
            float sideX = dir.x < 0 ? (start.x - mx) * dDist.x : (mx + 1.0f - start.x) * dDist.x;
            float sideY = dir.y < 0 ? (start.y - my) * dDist.y : (my + 1.0f - start.y) * dDist.y;
            float sideZ = dir.z < 0 ? (start.z - mz) * dDist.z : (mz + 1.0f - start.z) * dDist.z;

            float dist = 0.0f;
            while (dist < maxDist) {
                if (sideX < sideY) {
                    if (sideX < sideZ) { dist = sideX; sideX += dDist.x; mx += stepX; }
                    else { dist = sideZ; sideZ += dDist.z; mz += stepZ; }
                } else {
                    if (sideY < sideZ) { dist = sideY; sideY += dDist.y; my += stepY; }
                    else { dist = sideZ; sideZ += dDist.z; mz += stepZ; }
                }
                int lx = mx - oX; int ly = my - oY; int lz = mz - oZ;
                if (lx >= 0 && lx < size && ly >= 0 && ly < size && lz >= 0 && lz < size) {
                    if (voxels[lx + lz*size + ly*size*size] == VOXEL_SOLID) return dist;
                }
            }
            return maxDist;
        }
        
        float3 get_fibonacci_cone(int i, int n, float3 fwd, float spread) {
            float golden_angle = 2.399963f; 
            float z = 1.0f - ((float)i / (float)(n - 1)) * spread;
            float radius = sqrt(1.0f - z * z);
            float theta = golden_angle * i;
            float x = cos(theta) * radius;
            float y = sin(theta) * radius;
            float3 up = (fabs(fwd.y) < 0.99f) ? (float3)(0,1,0) : (float3)(1,0,0);
            float3 right = normalize(cross(up, fwd));
            up = cross(fwd, right);
            return right * x + up * y + fwd * z;
        }
    """;
}