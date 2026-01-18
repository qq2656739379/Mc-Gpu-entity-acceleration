package com.gpuaccel.entitymod.ai.kernel;

/**
 * OpenCL 内核公共代码库。
 * <p>
 * 包含随机数生成、噪声函数、向量操作及体素射线检测等基础工具函数。
 * 所有具体的 AI 逻辑内核都会包含此部分代码。
 * </p>
 */
public class KernelCommon {
    public static final String SRC = """
        // =========================================================
        // 状态与类型定义
        // =========================================================
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

        // =========================================================
        // 数学工具函数
        // =========================================================

        // 3D 哈希函数 (基于正弦波)
        float3 hash33(float3 p) {
            p = (float3)(dot(p, (float3)(127.1f, 311.7f, 74.7f)),
                         dot(p, (float3)(269.5f, 183.3f, 246.1f)),
                         dot(p, (float3)(113.5f, 271.9f, 124.6f)));
            float3 s = sin(p) * 43758.5453123f;
            return -1.0f + 2.0f * (s - floor(s));
        }
        
        // 伪随机数生成器 (线性同余法)
        uint next_rand(uint state) {
            return state * 1664525u + 1013904223u;
        }
        
        // 安全归一化：防止零向量导致 NaN 错误
        float3 safe_normalize(float3 v) {
            float lenSq = dot(v, v);
            if (lenSq < 1e-8f) return (float3)(0.0f);
            return v * rsqrt(lenSq);
        }

        // 旋度噪声 (Curl Noise)：生成无散度的伪随机向量场，用于模拟自然流动
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
            return safe_normalize(v);
        }

        // 限制向量长度不超过 maxVal
        float3 limit_vec(float3 v, float maxVal) {
            float lenSq = dot(v, v);
            if (lenSq > maxVal * maxVal && lenSq > 1e-6f) {
                return v * (maxVal * rsqrt(lenSq));
            }
            return v;
        }
        
        // 转向行为 (Steering Behavior)：计算转向力
        float3 steer(float3 currVel, float3 targetVel, float maxForce, float mass) {
            float3 desired = targetVel;
            float3 steering = desired - currVel;
            steering = limit_vec(steering, maxForce);
            return steering / mass; 
        }

        // 视野检查 (Field of View)
        bool in_fov(float3 fwd, float3 diff, float fovCos) {
            float3 dir = safe_normalize(diff);
            return dot(fwd, dir) > fovCos;
        }
        
        // Y 轴旋转
        float3 rotate_y(float3 v, float ang) {
            float c = cos(ang);
            float s = sin(ang);
            return (float3)(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
        }

        // =========================================================
        // 体素地图访问函数
        // =========================================================

        // 获取指定坐标的体素 ID
        char get_voxel(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            int ix = (int)floor(p.x) - oX;
            int iy = (int)floor(p.y) - oY;
            int iz = (int)floor(p.z) - oZ;
            if (ix >= 0 && ix < size && iy >= 0 && iy < size && iz >= 0 && iz < size) {
                return voxels[ix + iz*size + iy*size*size];
            }
            // 越界时默认返回空气
            return VOXEL_AIR; 
        }
        
        // 检查是否为固体障碍物
        bool is_solid(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            return get_voxel(p, voxels, oX, oY, oZ, size) == VOXEL_SOLID;
        }

        // 射线检测 (Raycast)：简单的 3D DDA 算法
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
        
        // 斐波那契螺旋采样：用于均匀分布的射线探测
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
