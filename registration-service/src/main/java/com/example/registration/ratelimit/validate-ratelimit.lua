-- A "leaky bucket" rate limiter controls the rate at which some action (identified by a given key) may be taken. This
-- implementation uses both a "permit regeneration" strategy (where a "bucket" has some maximum capacity for permits,
-- and the bucket refills over time) and "fixed rate" strategy, where actions can be taken at most once per unit time
-- even if the bucket has multiple permits available.
--
-- This implementation models buckets as "full" when they have permits available and "empty" when no permits are
-- available.
--
-- This script returns the duration in milliseconds before which an action will be allowed. If not positive, then the
-- action can be taken immediately. This script can be run in "read only" mode (see the `consumePermits` argument) to
-- check the time until an action is allowed without consuming permits or in "read/write" mode, where permits are
-- actually consumed.

local bucketKey = KEYS[1]

local bucketSize = tonumber(ARGV[1])
local permitRegenerationMillis = tonumber(ARGV[2])
local minDelayMillis = tonumber(ARGV[3])
local currentTimeMillis = tonumber(ARGV[4])
local consumePermits = ARGV[5] and string.lower(ARGV[5]) == "true"
local requestedAmount = 1



local PERMITS_REMAINING_FIELD = "p"
local TIME_FIELD = "t"

local permitsRemaning
local remaningCooldown
local lastUpdateTimeMillis

if redis.call("EXISTS", bucketKey) == 1 then
    local premitsRemStr, lastUpdateTimeMillis = unpack(redis.call("HMGET", bucketKey, PERMITS_REMAINING_FIELD, TIME_FIELD))
end
remaningCooldown = lastUpdateTimeMillis + minDelayMillis - currentTimeMillis

-- eclapsed time
local eclapsedMillis = currentTimeMillis - lastUpdateTimeMillis
-- avaialble permits
local availablePermits = math.min(
    bucketSize,
    permitsRemaning + (eclapsedMillis / permitRegenerationMillis)
)

if availablePermits >= requestedAmount and remaningCooldown <= 0 then 
    if consumePermits then

        permitsRemaning = availablePermits - requestedAmount
        lastUpdateTimeMillis = currentTimeMillis
        local permitsUsed = bucketSize - permitsRemaning
        if permitsUsed > 0 then
            -- added remaningCooldown in max
            -- key must be alive at least until cooldown ends, otherwise the next caller would get a fresh full bucket and skip the cooldown entirely.
            -- if (permitsUsed * permitRegenerationMillis) is 30s then key will expire without using remaningCooldown
            local ttl = max(
                remaningCooldown,
                math.ceil( permitsUsed * permitRegenerationMillis ) 
            )