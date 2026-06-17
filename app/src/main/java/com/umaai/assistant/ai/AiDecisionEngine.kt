package com.umaai.assistant.ai

import com.umaai.assistant.service.FloatingWindowService
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 赛马娘AI决策引擎 v2
 * 基于决策分析框架，完整实现训练/休息/外出/比赛的期望价值计算
 *
 * 参考: Decision Analysis Framework
 * EV_train = base_gain * success_rate * mood_multiplier - energy_cost * energy_value
 *         + bond_value - failure_penalty
 */
class AiDecisionEngine {

    // ========== 游戏常量 ==========
    companion object {
        // 设施等级基础增益 (Lv1~Lv5)
        val FACILITY_GAINS = intArrayOf(0, 7, 8, 10, 11, 12)

        // 训练体力消耗
        const val ENERGY_COST_TRAIN = 21
        const val ENERGY_COST_INT = 0   // 智力训练不耗体力

        // 休息恢复体力
        const val ENERGY_RECOVER_REST = 50

        // 外出恢复
        const val ENERGY_RECOVER_OUTING = 10
        const val MOOD_RECOVER_OUTING = 1

        // 成功率参数
        const val BASE_SUCCESS_RATE = 0.92
        const val MOTIVATION_SUCCESS_BONUS = 0.02  // 每级心情+2%
        const val ENERGY_SUCCESS_PENALTY = 0.015   // 体力每低1点-1.5%
        const val INTELLIGENCE_SUCCESS_BONUS = 0.001 // 每点智力+0.1%

        // 心情乘数 (絶不調~絶好調)
        val MOOD_MULTIPLIERS = doubleArrayOf(0.8, 0.9, 1.0, 1.1, 1.2)

        // 支援卡基础加成（估算，无具体卡数据时用默认值）
        const val DEFAULT_CARD_BONUS = 2.5
        const val BOND_MAX_BONUS = 0.20  // 满羁绊+20%

        // 属性上限
        const val STAT_CAP = 2100
        const val STAT_SOFT_CAP = 1200   // 超过后收益递减

        // 关键比赛回合
        val RACE_TURNS = setOf(12, 24, 36, 48, 60, 72)  // 目标G1大致回合
    }

    data class GameState(
        val speed: Int, val stamina: Int, val power: Int,
        val guts: Int, val wit: Int, val vital: Int,
        val motivation: Int, val turn: Int, val skillPt: Int,
        val isFat: Boolean
    )

    data class ActionEval(
        val action: String,
        val detail: String,
        val expectedValue: Double,
        val priority: Int
    )

    // ========== 主决策函数 ==========

    fun getAdvice(state: FloatingWindowService.AiStats): String {
        val gs = GameState(
            state.speed, state.stamina, state.power,
            state.guts, state.wit, state.vital,
            state.motivation, state.turn, state.skillPt,
            state.isFat
        )

        val actions = evaluateAllActions(gs)
        val sorted = actions.sortedByDescending { it.expectedValue }

        return buildString {
            // 当前状态摘要
            appendLine("【T${state.turn}】${state.skillPt}pt  体${state.vital}")
            appendLine("速${state.speed} 耐${state.stamina} 力${state.power} 根${state.guts} 智${state.wit}")

            // 心情
            val moodName = getMoodName(state.motivation)
            appendLine("心情: $moodName ${getMoodEmoji(state.motivation)}")

            // 阶段提示
            appendLine(getStageAdvice(state.turn))

            // 警告
            if (state.isFat) {
                appendLine("⚠️ 吃胖中！速度训练无效！")
            }
            if (state.vital < 20) {
                appendLine("⚠️ 体力危险！")
            }

            appendLine()

            // Top 3 建议
            val top = sorted.take(3)
            top.forEachIndexed { i, act ->
                val prefix = when (i) {
                    0 -> "⭐推荐"
                    1 -> "②备选"
                    else -> "③考虑"
                }
                appendLine("$prefix ${act.action}: ${act.detail}")
            }

            // 属性短板提示
            appendLine()
            appendLine(getImbalanceAdvice(gs))
        }
    }

    // ========== 评估所有行动 ==========

    private fun evaluateAllActions(s: GameState): List<ActionEval> {
        val evals = mutableListOf<ActionEval>()

        // 1. 五种训练
        val trainTypes = listOf(
            "速度" to s.speed,
            "耐力" to s.stamina,
            "力量" to s.power,
            "根性" to s.guts,
            "智力" to s.wit
        )

        for ((name, currentVal) in trainTypes) {
            // 吃胖时跳过速度
            if (s.isFat && name == "速度") continue

            val ev = evaluateTraining(s, name, currentVal)
            val nearCap = if (currentVal >= STAT_SOFT_CAP) "(已超${STAT_SOFT_CAP}，收益↓)" else ""
            evals.add(ActionEval(
                action = "训练$name",
                detail = "期望收益${ev.roundToInt()} $nearCap",
                expectedValue = ev,
                priority = 5
            ))
        }

        // 2. 休息
        val restEv = evaluateRest(s)
        evals.add(ActionEval(
            action = "休息",
            detail = "+${ENERGY_RECOVER_REST}体力",
            expectedValue = restEv,
            priority = if (s.vital < 30) 10 else 3
        ))

        // 3. 外出
        val outingEv = evaluateOuting(s)
        evals.add(ActionEval(
            action = "外出",
            detail = "+心情${if (s.vital < 50) " +${ENERGY_RECOVER_OUTING}体力" else ""}",
            expectedValue = outingEv,
            priority = if (s.motivation <= 2) 9 else 2
        ))

        // 4. 比赛（目标回合附近有比赛）
        if (isRaceTurn(s.turn)) {
            val raceEv = evaluateRace(s)
            evals.add(ActionEval(
                action = "比赛",
                detail = "目标G1 +35pt",
                expectedValue = raceEv,
                priority = 8
            ))
        }

        // 5. 保健室（有debuff时）
        if (s.isFat || s.motivation == 1) {
            evals.add(ActionEval(
                action = "保健室",
                detail = if (s.isFat) "消除吃胖" else "恢复绝不调",
                expectedValue = 999.0,  // 最高优先级
                priority = 10
            ))
        }

        // 6. 技能学习（pt充足时）
        if (s.skillPt > 200) {
            evals.add(ActionEval(
                action = "学技能",
                detail = "${s.skillPt}pt可学",
                expectedValue = s.skillPt * 0.02,
                priority = 4
            ))
        }

        return evals
    }

    // ========== 训练期望价值 ==========

    private fun evaluateTraining(s: GameState, statName: String, currentVal: Int): Double {
        // 1. 基础增益（估算设施等级Lv3~Lv4中间）
        val facilityLv = estimateFacilityLevel(s.turn)
        val baseGain = FACILITY_GAINS[facilityLv]

        // 2. 支援卡加成（估算平均3卡，羁绊中等）
        val cardBonus = DEFAULT_CARD_BONUS * 3
        val bondBonus = 1.0 + 0.10  // 中等羁绊+10%

        // 3. 心情乘数
        val moodIdx = (s.motivation - 1).coerceIn(0, 4)
        val moodMult = MOOD_MULTIPLIERS[moodIdx]

        // 4. 成功率
        val successRate = calculateSuccessRate(s, statName == "智力")

        // 5. 属性衰减（超过软上限后收益递减）
        val decay = if (currentVal > STAT_SOFT_CAP) {
            val over = currentVal - STAT_SOFT_CAP
            max(0.3, 1.0 - over / 1000.0)
        } else 1.0

        // 6. 期望属性增益
        val statGain = (baseGain + cardBonus) * bondBonus * moodMult * successRate * decay

        // 7. 体力惩罚
        val energyCost = if (statName == "智力") ENERGY_COST_INT else ENERGY_COST_TRAIN
        val energyValue = getMarginalEnergyValue(s.vital)
        val energyPenalty = energyCost * energyValue

        // 8. 失败惩罚
        val failurePenalty = (1 - successRate) * statGain * 0.5

        // 9. 羁绊价值（接近80时更高）
        val bondValue = estimateBondValue(s.turn)

        return statGain + bondValue - energyPenalty - failurePenalty
    }

    // ========== 休息期望价值 ==========

    private fun evaluateRest(s: GameState): Double {
        if (s.vital > 70) return -50.0  // 体力充足时休息无价值

        val recover = ENERGY_RECOVER_REST
        val energyValue = getMarginalEnergyValue(s.vital)
        val recoveredValue = recover * energyValue

        // 连续休息惩罚
        // val consecutivePenalty = if (wasRestLastTurn) -20.0 else 0.0

        // 紧急恢复加成
        val emergencyBonus = when {
            s.vital < 20 -> 100.0   // 体力极低，救命
            s.vital < 40 -> 50.0    // 体力低，重要
            s.vital < 60 -> 20.0    // 体力中等，建议
            else -> 0.0
        }

        return recoveredValue + emergencyBonus
    }

    // ========== 外出期望价值 ==========

    private fun evaluateOuting(s: GameState): Double {
        if (s.motivation >= 4) return -30.0  // 心情好调以上不需要外出

        val moodGap = (5 - s.motivation).toDouble()  // 心情缺口
        val moodValue = moodGap * 25.0  // 每级心情差值约25点训练收益

        // 体力恢复价值
        val energyValue = if (s.vital < 50) {
            ENERGY_RECOVER_OUTING * getMarginalEnergyValue(s.vital)
        } else 0.0

        // 紧急心情加成
        val emergencyBonus = when (s.motivation) {
            1 -> 150.0  // 绝不调，必须外出
            2 -> 80.0   // 不调，强烈建议
            else -> 0.0
        }

        return moodValue + energyValue + emergencyBonus
    }

    // ========== 比赛期望价值 ==========

    private fun evaluateRace(s: GameState): Double {
        // 比赛基础收益
        val ptReward = 35.0

        // 属性加成估算（五维平均vs比赛要求）
        val avgStat = (s.speed + s.stamina + s.power + s.guts + s.wit) / 5.0
        val winProb = min(0.95, avgStat / 800.0 * (0.9 + s.motivation * 0.03))

        // 机会成本 = 一次训练的价值
        val opportunityCost = estimateAvgTrainingValue(s) * 1.2

        // 失败惩罚
        val lossPenalty = if (winProb < 0.5) -15.0 else -5.0

        return winProb * ptReward + (1 - winProb) * lossPenalty - opportunityCost
    }

    // ========== 辅助计算 ==========

    private fun calculateSuccessRate(s: GameState, isIntelligence: Boolean): Double {
        val moodIdx = (s.motivation - 1).coerceIn(0, 4)
        var rate = BASE_SUCCESS_RATE +
                moodIdx * MOTIVATION_SUCCESS_BONUS -
                (100 - s.vital) * ENERGY_SUCCESS_PENALTY +
                s.wit * INTELLIGENCE_SUCCESS_BONUS

        // 体力极低惩罚
        if (s.vital < 20) rate -= 0.10
        if (s.vital < 10) rate -= 0.15

        // 智力训练成功率略高
        if (isIntelligence) rate += 0.03

        return rate.coerceIn(0.5, 0.98)
    }

    private fun getMarginalEnergyValue(currentEnergy: Int): Double {
        return when {
            currentEnergy > 50 -> 0.3
            currentEnergy > 30 -> 0.8
            currentEnergy > 15 -> 1.5
            currentEnergy > 5 -> 3.0
            else -> 5.0  // 极度危险
        }
    }

    private fun estimateFacilityLevel(turn: Int): Int {
        return when {
            turn <= 20 -> 2
            turn <= 40 -> 3
            turn <= 60 -> 4
            else -> 5
        }
    }

    private fun estimateBondValue(turn: Int): Double {
        // 早期羁绊价值高（尽快触发事件）
        return when {
            turn <= 30 -> 8.0
            turn <= 50 -> 5.0
            else -> 3.0
        }
    }

    private fun estimateAvgTrainingValue(s: GameState): Double {
        val avg = (s.speed + s.stamina + s.power + s.guts + s.wit) / 5.0
        val facilityLv = estimateFacilityLevel(s.turn)
        val baseGain = FACILITY_GAINS[facilityLv]
        val moodMult = MOOD_MULTIPLIERS[(s.motivation - 1).coerceIn(0, 4)]
        return (baseGain + 7.5) * 1.1 * moodMult
    }

    private fun isRaceTurn(turn: Int): Boolean {
        return RACE_TURNS.any { kotlin.math.abs(it - turn) <= 2 }
    }

    // ========== 建议文本 ==========

    private fun getStageAdvice(turn: Int): String {
        return when {
            turn <= 12 -> "【出道前】速提基础属性，优先速度和主属性"
            turn <= 24 -> "【出道后】准备G1，检查属性是否达标"
            turn <= 36 -> "【经典前半】皋月赏/德比目标，耐力要够"
            turn <= 48 -> "【经典后半】菊花赏/有马，长距离需耐力"
            turn <= 60 -> "【高级前半】大阪杯/天皇赏春"
            turn <= 72 -> "【高级后半】宝冢/天皇赏秋/有马，最终冲刺"
            else -> "【终盘】属性最终调整"
        }
    }

    private fun getImbalanceAdvice(s: GameState): String {
        val stats = listOf(
            "速度" to s.speed, "耐力" to s.stamina,
            "力量" to s.power, "根性" to s.guts, "智力" to s.wit
        )
        val avg = stats.map { it.second }.average()
        val minStat = stats.minByOrNull { it.second }
        val maxStat = stats.maxByOrNull { it.second }

        return buildString {
            if (minStat != null && minStat.second < avg * 0.7) {
                append("短板: ${minStat.first}(${minStat.second}) 远低于平均(${avg.roundToInt()})")
            }
            if (maxStat != null && maxStat.second > STAT_SOFT_CAP) {
                append(" | ${maxStat.first}已超${STAT_SOFT_CAP}，继续收益↓")
            }
            if (s.wit < 300 && s.turn > 30) {
                append(" | 智力偏低(${s.wit})，影响训练成功率和触发技能")
            }
            // 长距离build检测
            if (s.turn in 25..48 && s.stamina < 400) {
                append(" | 耐力不足！经典级长距离比赛危险")
            }
            // 速度build检测
            if (s.speed < 600 && s.turn > 40) {
                append(" | 速度不足，短距离/英里比赛不利")
            }
        }
    }

    private fun getMoodName(m: Int): String = when (m) {
        1 -> "绝不调"
        2 -> "不调"
        3 -> "普通"
        4 -> "好调"
        5 -> "绝好调"
        else -> "普通"
    }

    private fun getMoodEmoji(m: Int): String = when (m) {
        1 -> "😫"
        2 -> "😟"
        3 -> "😐"
        4 -> "🙂"
        5 -> "😄"
        else -> "😐"
    }
}
