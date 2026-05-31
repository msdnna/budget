package models

import "time"

// IntentTriggerKinds — канонический список намерений telegram-бота, которые
// несут настраиваемые админом фразы-триггеры. "transaction" сюда НЕ входит:
// это намерение по умолчанию, оно срабатывает когда ни одно из перечисленных
// не подошло, и собственных триггеров не имеет.
var IntentTriggerKinds = []string{
	"wishlist",
	"recurring_payment",
	"link_existing",
	"detail_request",
}

// IsIntentTriggerKind reports whether the given intent name is one the admin
// can attach trigger phrases to.
func IsIntentTriggerKind(intent string) bool {
	for _, k := range IntentTriggerKinds {
		if k == intent {
			return true
		}
	}
	return false
}

// DefaultIntentPhrases — встроенный набор фраз-триггеров, единственный источник
// правды. Сидится в коллекцию `intent_triggers` при старте (EnsureDefaults) как
// обычные записи — после посева бот их больше нигде не хардкодит, а админ может
// свободно править/удалять. Ключи — намерения из IntentTriggerKinds.
var DefaultIntentPhrases = map[string][]string{
	"wishlist": {
		"хочу купить",
		"планирую купить",
		"хочу приобрести",
		"мечтаю купить",
		"надо купить",
		"хочу заказать",
		"присматриваю",
	},
	"recurring_payment": {
		"оплатил счёт",
		"оплатил счет",
		"оплата счёта",
		"платёжка",
		"платежка",
		"жкх",
		"коммуналка",
		"квартплата",
		"страховка",
		"абонентская плата",
		"ежемесячный платёж",
		"ежемесячный платеж",
		"оплатил за",
	},
	"link_existing": {
		"привяжи",
		"привязать",
		"прицепи",
		"связать расход",
		"привяжи расход",
	},
	"detail_request": {
		"создай знд",
		"создать знд",
		"открой знд",
		"запрос на детализацию",
		"знд на",
		"детализацию на",
	},
}

// IntentTrigger — один документ на намерение со списком пользовательских
// фраз-подсказок. Бот мерджит их ПОВЕРХ собственных встроенных дефолтов
// (аддитивно), поэтому пустой список просто означает «доп. фраз нет», а не
// «отключить намерение».
type IntentTrigger struct {
	ID        string    `bson:"_id" json:"id"`
	Intent    string    `bson:"intent" json:"intent"`
	Phrases   []string  `bson:"phrases" json:"phrases"`
	UpdatedAt time.Time `bson:"updated_at" json:"updated_at"`
}

// UpdateIntentTriggerRequest — полная замена списка фраз для намерения
// (PUT-семантика, не patch — список целиком приходит из UI).
type UpdateIntentTriggerRequest struct {
	Phrases []string `json:"phrases"`
}
